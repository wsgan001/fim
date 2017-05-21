package org.openu.fimcmp.fin;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.spark.HashPartitioner;
import org.apache.spark.Partitioner;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.openu.fimcmp.FreqItemset;
import org.openu.fimcmp.algbase.AlgBase;
import org.openu.fimcmp.algbase.F1Context;
import org.openu.fimcmp.props.CmdLineOptions;
import org.openu.fimcmp.result.BitsetFiResultHolderFactory;
import org.openu.fimcmp.result.CountingOnlyFiResultHolderFactory;
import org.openu.fimcmp.result.FiResultHolder;
import org.openu.fimcmp.result.FiResultHolderFactory;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Implement the main steps of the FIN+ algorithm.
 */
public class FinAlg extends AlgBase<FinAlgProperties, Void> {

    //--spark-master-url local --input-file-name pumsb.dat --min-supp 0.8 --input-parts-num 1 --persist-input true --run-type PAR_SPARK --itemset-len-for-seq-processing 1 --cnt-only true
    public static void main(String[] args) throws Exception {
        FinCmdLineOptionsParser cmdLineOptionsParser = new FinCmdLineOptionsParser();
        CmdLineOptions<FinAlgProperties> runProps = cmdLineOptionsParser.parseCmdLine(args);
        if (runProps == null) {
            return; //help
        }

        StopWatch sw = new StopWatch();
        sw.start();
        pp(sw, runProps);
        JavaSparkContext sc = createSparkContext(runProps.isUseKrio, runProps.sparkMasterUrl, sw);

        FinAlg alg = cmdLineOptionsParser.createAlg(runProps);
        alg.run(sc, sw);
    }

    public static Class[] getClassesToRegister() {
        return new Class[]{
                FinAlgProperties.class, ProcessedNodeset.class, DiffNodeset.class, PpcNode.class,
                FinAlgHelper.class,
                Predicate.class
        };
    }

    @SuppressWarnings("WeakerAccess")
    public FinAlg(FinAlgProperties props, String inputFile) {
        super(props, inputFile);
    }

    @Override
    public Void run(JavaSparkContext sc, StopWatch sw) throws Exception {
        FinContext ctx = prepareContext(sc, sw);

        FiResultHolder resultHolder;
        switch (props.runType) {
            case SEQ_PURE_JAVA:
                resultHolder = collectResultsSequentiallyPureJava(
                        ctx.resultHolderFactory, ctx.rankTrsRdd, ctx.f1Context,
                        props.requiredItemsetLenForSeqProcessing);
                break;
            case SEQ_SPARK:
                resultHolder = collectResultsSequentiallyWithSpark(
                        ctx.resultHolderFactory, ctx.rankTrsRdd, ctx.f1Context,
                        props.requiredItemsetLenForSeqProcessing, sc);
                break;
            case PAR_SPARK:
                resultHolder = collectResultsInParallel(ctx.resultHolderFactory, ctx.rankTrsRdd, ctx.f1Context, props);
                break;
            default:
                throw new IllegalArgumentException("Unsupported run type " + props.runType);
        }

        outputResults(resultHolder, ctx.f1Context, sw);
        return null;
    }

    private FinContext prepareContext(JavaSparkContext sc, StopWatch sw) {
        FinContext res = new FinContext();
        res.alg = this;
        res.sw = sw;
        res.sc = sc;

        JavaRDD<String[]> trs = readInput(sc, sw);

        res.f1Context = computeF1Context(trs, sw);
        res.f1Context.printRankToItem();
        res.rankTrsRdd = res.f1Context.computeRddRanks1(trs);

        res.resultHolderFactory = (props.isCountingOnly) ?
                new CountingOnlyFiResultHolderFactory(res.f1Context.totalFreqItems) :
                new BitsetFiResultHolderFactory(res.f1Context.totalFreqItems, 20_000);

        return res;
    }

    private FiResultHolder collectResultsInParallel(
            FiResultHolderFactory resultHolderFactory, JavaRDD<int[]> data,
            F1Context f1Context, FinAlgProperties props) {
        int numParts = data.getNumPartitions();
        Partitioner partitioner = new HashPartitioner(numParts);
        JavaPairRDD<Integer, int[]> partToRankTrsRdd =
                data.flatMapToPair(tr -> FinAlgHelper.genCondTransactions(tr, partitioner));
        return FinAlgHelper.findAllFisByParallelFin(
                partToRankTrsRdd, partitioner, resultHolderFactory, f1Context, props);
    }


    private FiResultHolder collectResultsSequentiallyWithSpark(
            FiResultHolderFactory resultHolderFactory, JavaRDD<int[]> rankTrsRdd, F1Context f1Context,
            int requiredItemsetLenForSeqProcessing, JavaSparkContext sc) {

        FiResultHolder rootsResultHolder = resultHolderFactory.newResultHolder();
        List<ProcessedNodeset> rootNodesets = FinAlgHelper.createAscFreqSortedRoots(
                rootsResultHolder, rankTrsRdd, f1Context, requiredItemsetLenForSeqProcessing);
        //nodes sorted in descending frequency, but from the largest sub-tree
        // to the smallest one consisting just of the most frequent item:
        JavaRDD<ProcessedNodeset> rootsRdd = sc.parallelize(rootNodesets);

        //process each subtree
        FiResultHolder initResultHolder = resultHolderFactory.newResultHolder();
        final long minSuppCnt = f1Context.minSuppCnt;
        FiResultHolder subtreeResultHolder = rootsRdd
                .map(pn -> pn.processSubtree(resultHolderFactory, minSuppCnt))
                .fold(initResultHolder, FiResultHolder::uniteWith);

        return rootsResultHolder.uniteWith(subtreeResultHolder);
    }

    private FiResultHolder collectResultsSequentiallyPureJava(
            FiResultHolderFactory resultHolderFactory, JavaRDD<int[]> rankTrsRdd, F1Context f1Context,
            int requiredItemsetLenForSeqProcessing) {

        FiResultHolder resultHolder = resultHolderFactory.newResultHolder();
        List<ProcessedNodeset> rootNodesets = FinAlgHelper.createAscFreqSortedRoots(
                resultHolder, rankTrsRdd, f1Context, requiredItemsetLenForSeqProcessing);

        //process each subtree
        for (ProcessedNodeset rootNodeset : rootNodesets) {
            System.out.println(String.format("Processing subtree of %s", Arrays.toString(rootNodeset.getItemset())));
            long sizeBefore = resultHolder.size();
            rootNodeset.processSubtree(resultHolder, f1Context.minSuppCnt);
            System.out.println(String.format("Done processing subtree of %s: +%s -> %s",
                    Arrays.toString(rootNodeset.getItemset()), resultHolder.size() - sizeBefore, resultHolder.size()));
        }

        return resultHolder;
    }

    private void outputResults(FiResultHolder resultHolder, @SuppressWarnings("UnusedParameters") F1Context f1Context, StopWatch sw) {
        pp(sw, "Total results: " + resultHolder.size());
        if (!props.isCountingOnly) {
            List<FreqItemset> allFrequentItemsets = resultHolder.getAllFrequentItemsets(f1Context.rankToItem);
            if (props.isPrintAllFis) {
                allFrequentItemsets = allFrequentItemsets.stream().
                        sorted(FreqItemset::compareForNiceOutput2).collect(Collectors.toList());
                allFrequentItemsets.forEach(System.out::println);
            }
            pp(sw, "Total results: " + allFrequentItemsets.size());
        }
    }

    private static class FinContext {
        StopWatch sw;
        JavaSparkContext sc;
        F1Context f1Context;
        FinAlg alg;
        JavaRDD<int[]> rankTrsRdd;
        FiResultHolderFactory resultHolderFactory;
    }
}
