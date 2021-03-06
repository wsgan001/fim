package org.openu.fimcmp.algs.bigfim;

import org.openu.fimcmp.algs.algbase.CommonAlgProperties;

/**
 * Holds all the properties for BigFim algorithm.
 */
@SuppressWarnings("WeakerAccess")
public class BigFimAlgProperties extends CommonAlgProperties {
    /**
     * Determines when to stop Apriori an switch to Eclat. <br/>
     * E.g. prefixLenToStartEclat=2 means that the Apriori should compute F3 (so that the prefix size is 2). <br/>
     * Note that the algorithm could still continue with Apriori if it decides that the dataset is 'sparse',
     * see {@link #currToPrevResSignificantIncreaseRatio}.
     */
    public final int prefixLenToStartEclat;

    //BigFim
    /**
     * Decides whether the itemset is 'sparse' enough to continue with Apriori despite
     * the {@link #prefixLenToStartEclat} restriction. <br/>
     * The idea is that Apriori is very fast on sparse datasets. <br/>
     */
    public double currToPrevResSignificantIncreaseRatio = 1.1;
    //Eclat
    public boolean isUseDiffSets = true;
    public boolean isSqueezingEnabled = false;
    public Integer maxEclatNumParts;

    public BigFimAlgProperties(double minSupp, int prefixLenToStartEclat) {
        super(minSupp);
        this.prefixLenToStartEclat = prefixLenToStartEclat;
    }
}
