package org.openu.fimcmp.apriori;

import org.openu.fimcmp.util.Assert;
import org.openu.fimcmp.util.BitArrays;
import scala.Tuple2;
import scala.Tuple3;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

/**
 * Set of TIDs optimized for merge operations. <br/>
 * Holds everything in long[]. <br/>
 * To allow working with RDD&lt;long[]> rather than with RDD&lt;TidMergeSet>,
 * has all its operations as static, taking long[] as its first argument. <br/>
 * <b>WARNING: assumes all the TIDs are in the range [0, total), see RDD.zipWithIndex()</b><br/>
 * <p>
 * <pre>
 * The array structure is: {rank, size, firstElem, lastElem, (bitset of TIDs)}.
 * Bitset of TIDs:
 * - The size is 2 x total / 64
 * - Each even element holds a bitset for the 64 TIDs in the range [ii, ii+63], where ii = elem index / 2
 * - Each odd element holds a pointer to the next element
 * This way we
 * (1) Avoid re-allocations
 * (2) merge(s1, s2) only takes O(s2.size / 64) operations,
 *     e.g. if it holds only 2 elements it will take about 2 operations
 * </pre>
 */
public class TidMergeSet implements Serializable {
    private static final int RANK_IND = 0;
    private static final int SIZE_IND = 1;
    private static final int MIN_ELEM_IND = 2;
    private static final int MAX_ELEM_IND = 3;
    private static final int MIN_TID_IN_PARTITION_IND = 4;
    private static final int MAX_TID_IN_PARTITION_IND = 5;
    private static final int BITSET_START_IND = 6;

    /**
     * Return an iterator over a single long[][] array: rankK -> 'tid-set'. <br/>
     * The 'tid-set' is a bitset of TIDs prefixed with some metadata.
     */
    static Iterator<long[][]> processPartition_ShortTidSet(
            Iterator<Tuple2<long[], Long>> kRanksBsAndTidIt,
            TidsGenHelper tidsGenHelper,
            Tuple2<Long, Long> minAndMaxTids) {
        final int RANKS_BITSET_START_IND = 0;
        long[][] rankKToTidSet = new long[tidsGenHelper.totalRanks()][];
        boolean hasElems = false;

        final long minTidInPartition = minAndMaxTids._1;
        final long maxTidInPartition = minAndMaxTids._2;
        final int totalTidsInThisPartition = 1 + (int)(maxTidInPartition - minTidInPartition);
        while(kRanksBsAndTidIt.hasNext()) {
            hasElems = true;
            Tuple2<long[], Long> kRanksBsAndTid = kRanksBsAndTidIt.next();
            long[] kRanksBs = kRanksBsAndTid._1;

            long[] kRanksToBeStoredBs = new long[kRanksBs.length];
            tidsGenHelper.setToResRanksToBeStoredBitSet(kRanksToBeStoredBs, RANKS_BITSET_START_IND, kRanksBs);
            int[] kRanksToBeStored = BitArrays.asNumbers(kRanksToBeStoredBs, RANKS_BITSET_START_IND);

            final long tidToStore = kRanksBsAndTid._2 - minTidInPartition; //storing a smaller number than TID
            for (int rankK : kRanksToBeStored) {
                long[] tidSet = rankKToTidSet[rankK];
                if (tidSet != null) {
                    BitArrays.set(tidSet, BITSET_START_IND, (int) tidToStore); //requires to set min, max and size later
                } else {
                    rankKToTidSet[rankK] = newSetWithElem(rankK, tidToStore, totalTidsInThisPartition);
                    rankKToTidSet[rankK][MIN_TID_IN_PARTITION_IND] = minTidInPartition;
                    rankKToTidSet[rankK][MAX_TID_IN_PARTITION_IND] = maxTidInPartition;
                }
            }
        }

        if (hasElems) {
            for (long[] tidSet : rankKToTidSet) {
                setMetadata_ShortTidSet(tidSet);
            }
        }

        return Collections.singletonList(rankKToTidSet).iterator();
    }

    static Iterator<Tuple3<Integer, Long, Long>> findMinAndMaxTids_ShortTidSet(
            Integer partitionIndex,
            Iterator<Tuple2<long[], Long>> kRanksBsAndTidIt) {
        long minTid = Long.MAX_VALUE;
        long maxTid = Long.MIN_VALUE;
        while (kRanksBsAndTidIt.hasNext()) {
            Tuple2<long[], Long> kRanksBsAndTid = kRanksBsAndTidIt.next();
            long tid = kRanksBsAndTid._2;
            minTid = Math.min(minTid, tid);
            maxTid = Math.max(maxTid, tid);
        }
        return Collections.singletonList(new Tuple3<>(partitionIndex, minTid, maxTid)).iterator();
    }

    static long[][] mergePartitions_ShortTidSet(long[][] part1, long[][] part2, TidsGenHelper tidsGenHelper) {
        if (part2.length == 0) {
            return part1;
        }
        final int totalRanks = tidsGenHelper.totalRanks();
        if (part1.length == 0) {
            part1 = new long[totalRanks][];
        }

        for (int rankK = 0; rankK < totalRanks; ++rankK) {
            part1[rankK] = mergeTidSets_ShortTidSet(part1[rankK], part2[rankK]);
        }
        return part1;
    }


    /**
     * Assuming the two sets' ranges do not intersect
     */
    private static long[] mergeTidSets_ShortTidSet(long[] s1, long[] s2) {
        if (s2 == null || s2.length <= 1) {
            return s1;
        }
        if (s1 == null || s1.length <= 1) {
            return copyOf(s2);
        }

        final boolean s1IsLower = s1[MIN_ELEM_IND] < s2[MIN_ELEM_IND];
        long[] lowerSet = s1IsLower ? s1 : s2;
        long[] higherSet = s1IsLower ? s2 : s1;
        Assert.isTrue(lowerSet[MAX_ELEM_IND] < higherSet[MIN_ELEM_IND]);
        Assert.isTrue(lowerSet[MAX_TID_IN_PARTITION_IND] < higherSet[MIN_TID_IN_PARTITION_IND]);

        int maxMinusCorrection = (int) (higherSet[MAX_TID_IN_PARTITION_IND] - lowerSet[MIN_TID_IN_PARTITION_IND]);
        long[] res = new long[BitArrays.requiredSize(1 + maxMinusCorrection, BITSET_START_IND)];
        res[RANK_IND] = lowerSet[RANK_IND];
        res[SIZE_IND] = lowerSet[SIZE_IND] + higherSet[SIZE_IND];
        res[MIN_ELEM_IND] = lowerSet[MIN_ELEM_IND];
        res[MAX_ELEM_IND] = higherSet[MAX_ELEM_IND];
        res[MIN_TID_IN_PARTITION_IND] = lowerSet[MIN_TID_IN_PARTITION_IND];
        res[MAX_TID_IN_PARTITION_IND] = higherSet[MAX_TID_IN_PARTITION_IND];

        int lowerSetCopyLen = lowerSet.length - BITSET_START_IND;
        System.arraycopy(lowerSet, BITSET_START_IND, res, BITSET_START_IND, lowerSetCopyLen);

        int higherSetCopyLen = higherSet.length - BITSET_START_IND;
        int higherSetDestStartInd = BitArrays.wordIndex((int)higherSet[MIN_TID_IN_PARTITION_IND], BITSET_START_IND);
        System.arraycopy(higherSet, BITSET_START_IND, res, higherSetDestStartInd, higherSetCopyLen);

        return res;
    }

    private static void setMetadata_ShortTidSet(long[] tidSet) {
        if (tidSet == null) {
            return;
        }

        long correction = tidSet[MIN_TID_IN_PARTITION_IND];
        tidSet[MIN_ELEM_IND] = correction + BitArrays.min(tidSet, BITSET_START_IND);
        tidSet[MAX_ELEM_IND] = correction + BitArrays.max(tidSet, BITSET_START_IND);
        tidSet[SIZE_IND] = BitArrays.cardinality(tidSet, BITSET_START_IND);
    }

    static long[] describeAsList(long[] tidSet) {
        long[] res = new long[4];
        res[0] = tidSet[RANK_IND];
        if (tidSet.length > 1) {
            res[1] = tidSet[SIZE_IND];
            res[2] = tidSet[MIN_ELEM_IND];
            res[3] = tidSet[MAX_ELEM_IND];
//            res.add((long)count(tidSet));
        }

        return res;
    }


    private static long[] copyOf(long[] s2) {
        return Arrays.copyOf(s2, s2.length);
    }

    private static long[] newSetWithElem(int rank, long tid, long totalTids) {
        long[] res = new long[BitArrays.requiredSize((int) totalTids, BITSET_START_IND)];
        res[RANK_IND] = rank;
        res[SIZE_IND] = 1;
        int tidAsInt = (int) tid;
        res[MIN_ELEM_IND] = res[MAX_ELEM_IND] = tidAsInt;
        BitArrays.set(res, BITSET_START_IND, tidAsInt);
        return res;
    }
}
