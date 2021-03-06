package org.openu.fimcmp.algs.algbase;

import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

import java.io.Serializable;

/**
 * Properties relevant to all algorithms
 */
@SuppressWarnings("WeakerAccess")
public class CommonAlgProperties implements Serializable {
    /**
     * Min support as a ratio of itemset frequency to the total number of transactions
     */
    public final double minSupp;

    /**
     * How many partitions to use to read the input file. <br/>
     * In the actual distributed environment this is the number of physical machines holding different parts of the file.
     */
    public int inputNumParts = 2;

    public boolean isPersistInput = false;

    public boolean isPrintIntermediateRes = true;

    /**
     * Note: it's impossible to apply it to the entire algorithm if it uses Apriori, as Apriori can't proceed with just counting: <br/>
     * Apriori needs Fk-1 in order to compute Fk
     */
    public boolean isCountingOnly = true;

    public boolean isPrintAllFis = false;

    protected CommonAlgProperties(double minSupp) {
        this.minSupp = minSupp;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE);
    }
}
