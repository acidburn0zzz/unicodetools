/**
 *******************************************************************************
 * Copyright (C) 1996-2001, International Business Machines Corporation and    *
 * others. All Rights Reserved.                                                *
 *******************************************************************************
 *
 * $Source: /home/cvsroot/unicodetools/org/unicode/text/UCA/UCA.java,v $ 
 *
 *******************************************************************************
 */

package org.unicode.text.UCA;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.unicode.text.UCA.UCA_Statistics.RoBitSet;
import org.unicode.text.UCD.Default;
import org.unicode.text.UCD.Normalizer;
import org.unicode.text.UCD.UCD;
import org.unicode.text.UCD.UCD_Types;
import org.unicode.text.utility.IntStack;
import org.unicode.text.utility.UTF16Plus;
import org.unicode.text.utility.Utility;

import com.ibm.icu.text.UTF16;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.text.UnicodeSetIterator;

//import org.unicode.text.CollationData.*;

/**
 * UCA is a working version of the UTS #10 Unicode Collation Algorithm,
 * as described on http://www.unicode.org/unicode/reports/tr10/
 * @author Mark Davis

<p>It is not optimized, although it does use some techniques that are required for
a real optimization, such as squeezing all the weights into 32 bits.

<p>Invariants relied upon by the algorithm:

<p>UCA Data:
<ol>
  <li>Tertiary values are less than 0x80
  <li>Variables (marked with *), have a distinct, closed range of primaries. 
    That is, there are no variable CEs X, Z and non-ignorable CE Y such that X[1] <= Y[1] <= Z[1]<br>
    This saves a bit in each CE.
  <li>It needs to be fixed when reading: only non-zero weights (levels 1-3) are really variable!
</ol>

<p>Limits: If any of the weight limits are reached (FFFF for primary, 1FF for secondary, 7F for tertiary),
expanding characters can be used to achieve the right results, as discussed in UTR#10.

<p>Remarks:
<p>Neither the old 14651 nor the old UCA algorithms for backwards really worked.
This is because of shared
characters between scripts with different directions, like French with Arabic or Greek.
 */

final public class UCA implements Comparator, UCA_Types {

    public static final int TEST_PRIMARY = 0xFDFC;

    public enum CollatorType {ducet, cldr, cldrWithoutFFFx}

    public static final String copyright = 
        "Copyright (C) 2000, IBM Corp. and others. All Rights Reserved.";

    public int compare(Object a, Object b) {
        return getSortKey((String) a).compareTo(getSortKey((String) b));
    }


    /**
     * Records the codeversion
     */
    private static final String codeVersion = "7";

    // base directory will change depending on the installation
    public static final String BASE_DIR = UCD_Types.BASE_DIR;


    // =============================================================
    // Test Settings
    // =============================================================
    static final boolean DEBUG = false;
    static final boolean DEBUG_SHOW_LINE = false;

    static final boolean SHOW_STATS = true;

    static final boolean SHOW_CE = false;
    static final boolean CHECK_UNIQUE = false;
    static final boolean CHECK_UNIQUE_EXPANSIONS = false; // only effective if CHECK_UNIQUE
    static final boolean CHECK_UNIQUE_VARIABLES = false; // only effective if CHECK_UNIQUE
    static final boolean TEST_BACKWARDS = false;
    static final boolean RECORDING_DATA = false;
    static final boolean RECORDING_CHARS = true;

    private UCD ucd;
    private UCA_Data ucaData;

    // =============================================================
    // Main Methods
    // =============================================================

    private String fileVersion = "??";

    /**
     * Initializes the collation from a stream of rules in the normal formal.
     * If the source is null, uses the normal Unicode data files, which
     * need to be in BASE_DIR.
     * @param type 
     */
    public UCA(String sourceFile, String unicodeVersion, Remap primaryRemap) throws java.io.IOException {
        fullData = sourceFile == null;
        fileVersion = sourceFile;

        // load the normalizer
        if (toD == null) {
            toD = new Normalizer(Normalizer.NFD, unicodeVersion);
            toKD = new Normalizer(Normalizer.NFKD, unicodeVersion);
        }

        ucd = UCD.make(unicodeVersion);
        ucdVersion = ucd.getVersion();

        ucaData = new UCA_Data(toD, ucd, primaryRemap);

        // either get the full sources, or just a demo set
        /*        if (fullData) {      
            for (int i = 0; i < KEYS.length; ++i) {
                BufferedReader in = new BufferedReader(
                    new FileReader(KEYS[i]), BUFFER_SIZE);
                addCollationElements(in);
                in.close();
            }
        } else */
        {
            BufferedReader in = new BufferedReader(
                    new FileReader(sourceFile), BUFFER_SIZE);
            addCollationElements(in);
            in.close();
        }
        cleanup();
    }

    /**
     * Constructs a sort key for given CEs.
     * @param ces collation elements
     * @param alternate choice of different 4th level weight construction
     * @param appendIdentical whether to append an identical level, and which kind of one
     * @return Result is a String not really of Unicodes, but of weights.
     * String is just a handy way of returning them in Java, since there are no
     * unsigned shorts.
     */
    public String getSortKey(CEList ces, byte alternate, AppendToCe appendIdentical) {
        return getSortKey(ces, "", alternate, defaultDecomposition, appendIdentical);
    }

    /**
     * Constructs a sort key for a string of input Unicode characters. Uses
     * default values for alternate and decomposition.
     * @param sourceString string to make a sort key for.
     * @return Result is a String not of really of Unicodes, but of weights.
     * String is just a handy way of returning them in Java, since there are no
     * unsigned shorts.
     */
    public String getSortKey(String sourceString) {
        return getSortKey(null, sourceString, defaultAlternate, defaultDecomposition, AppendToCe.none);
    }
    /**
     * Constructs a sort key for a string of input Unicode characters. Uses
     * default value decomposition.
     * @param sourceString string to make a sort key for.
     * @param alternate choice of different 4th level weight construction
     * @return Result is a String not of really of Unicodes, but of weights.
     * String is just a handy way of returning them in Java, since there are no
     * unsigned shorts.
     */

    public String getSortKey(String sourceString, byte alternate) {
        return getSortKey(null, sourceString, alternate, defaultDecomposition, AppendToCe.none);
    }

    public static final int CE_FFFE = UCA.makeKey(0x1, 0x20, 0x5);

    public enum AppendToCe {none, nfd, tieBreaker}

    private void setSourceString(String sourceString, boolean decomposition) {
        decompositionBuffer.setLength(0);
        if (decomposition) {
            toD.normalize(sourceString, decompositionBuffer);
        } else {
            decompositionBuffer.append(sourceString);
        }
        index[0] = 0;
    }

    /**
     * Constructs a sort key for a string of input Unicode characters.
     * @param sourceString string to make a sort key for.
     * @param alternate choice of different 4th level weight construction
     * @param decomposition true for UCA, false where the text is guaranteed to be
     * normalization form C with no combining marks of class 0.
     * @param appendIdentical whether to append an identical level, and which kind of one
     * @return Result is a String not of really of Unicodes, but of weights.
     * String is just a handy way of returning them in Java, since there are no
     * unsigned shorts.
     */
    public String getSortKey(String sourceString, byte alternate, boolean decomposition, AppendToCe appendIdentical) {
        return getSortKey(null, sourceString, alternate, decomposition, appendIdentical);
    }

    /**
     * Constructs a sort key for given CEs, and/or a string of input Unicode characters.
     * When the CEs are used up, then the sourceString is processed.
     * @param ces collation elements to be considered first, can be null
     * @param sourceString string to make a sort key for, can be empty but not null
     * @param alternate choice of different 4th level weight construction
     * @param decomposition true for UCA, false where the text is guaranteed to be
     * normalization form C with no combining marks of class 0.
     * @param appendIdentical whether to append an identical level, and which kind of one
     * @return Result is a String not really of Unicodes, but of weights.
     * String is just a handy way of returning them in Java, since there are no
     * unsigned shorts.
     */
    public String getSortKey(CEList ces, String sourceString, byte alternate, boolean decomposition, AppendToCe appendIdentical) {
        setSourceString(sourceString, decomposition);

        // Weight strings - not chars, weights.
        primaries.setLength(0);             // clear out
        secondaries.setLength(0);           // clear out
        tertiaries.setLength(0);            // clear out
        quaternaries.setLength(0);          // clear out
        if (SHOW_CE) debugList.setLength(0); // clear out

        char weight4 = '\u0000'; // DEFAULT FOR NON_IGNORABLE
        boolean lastWasVariable = false;

        // process CEs, building weight strings
        int cesIndex = 0;
        int cesLength = ces == null ? 0 : ces.length();
        while (true) {
            int ce;
            if (cesIndex < cesLength) {
                ce = ces.at(cesIndex++);
                if (ce == 0) {
                    continue;
                }
            } else {
                ces = nextCEs();
                if (ces == null) {
                    break;
                }
                cesIndex = 0;
                cesLength = ces.length();
                continue;
            }

            switch (alternate) {
            case ZEROED:
                if (isVariable(ce)) {
                    ce = 0;
                }
                break;
            case SHIFTED_TRIMMED:
            case SHIFTED:
                if (ce == 0) {
                    weight4 = 0;
                } else if (ce == CE_FFFE) { // variables
                    weight4 = getPrimary(ce);
                    lastWasVariable = false;
                } else if (isVariable(ce)) { // variables
                    weight4 = getPrimary(ce);
                    lastWasVariable = true;
                    ce = 0;
                } else if (lastWasVariable && getPrimary(ce) == 0) { // zap trailing ignorables
                    ce = 0;
                    weight4 = 0;
                } else { // above variables
                    lastWasVariable = false;
                    weight4 = '\uFFFF';
                }
                break;
                // case NON_IGNORABLE: // doesn't ever change!
            }
            if (SHOW_CE) {
                if (debugList.length() != 0) debugList.append("/");
                debugList.append(CEList.toString(ce));
            }

            // add weights
            char w = getPrimary(ce);
            if (DEBUG) System.out.println("\tCE: " + Utility.hex(ce));
            if (w != 0) {
                primaries.append(w);
            }

            w = getSecondary(ce);
            if (w != 0) {
                if (!useBackwards) {
                    secondaries.append(w);
                } else {
                    secondaries.insert(0, w);
                }
            }

            w = getTertiary(ce);
            if (w != 0) {
                tertiaries.append(w);
            }

            if (weight4 != 0) {
                quaternaries.append(weight4);
            }
        }

        // Produce weight strings
        // For simplicity, we use the strength setting here.
        // To optimize, we wouldn't actually generate the weights in the first place.

        StringBuilder result = primaries;
        if (strength >= 2) {
            result.append(LEVEL_SEPARATOR);    // separator
            result.append(secondaries);
            if (strength >= 3) {
                result.append(LEVEL_SEPARATOR);    // separator
                result.append(tertiaries);
                if (strength >= 4) {
                    result.append(LEVEL_SEPARATOR);    // separator
                    if (alternate == SHIFTED_TRIMMED) {
                        int q;
                        for (q = quaternaries.length()-1; q >= 0; --q) {
                            if (quaternaries.charAt(q) != '\uFFFF') {
                                break;
                            }
                        }
                        quaternaries.setLength(q+1);
                    }
                    result.append(quaternaries);
                    //appendInCodePointOrder(decompositionBuffer, result);
                }
            }
        }
        if (appendIdentical != AppendToCe.none) {
            String cpo = UCA.codePointOrder(toD.normalize(sourceString));
            result.append('\u0000').append(cpo);
            if (appendIdentical == AppendToCe.tieBreaker) {
                cpo = UCA.codePointOrder(sourceString);
                result.append('\u0000').append(cpo).append((char) cpo.length());
            }
        }
        return result.toString();
    }

    // 0 ==
    // 2, -2 quarternary
    // 3, -3 tertiary
    // 4, -4 secondary
    // 5, -5 primary

    public static int strengthDifference(String sortKey1, String sortKey2) {
        int len1 = sortKey1.length();
        int len2 = sortKey2.length();
        int minLen = len1 < len2 ? len1 : len2;
        int strength = 5;
        for (int i = 0; i < minLen; ++i) {
            char c1 = sortKey1.charAt(i);
            char c2 = sortKey2.charAt(i);
            if (c1 < c2) return -strength;
            if (c1 > c2) return strength;
            if (c1 == LEVEL_SEPARATOR) --strength; // Separator!
        }
        if (len1 < len2) return -strength;
        if (len1 > len2) return strength;
        return 0;
    }

    /**
     * Turns backwards (e.g. for French) on globally for all secondaries
     */
    public void setBackwards(boolean backwards) {
        useBackwards = backwards;
    }

    /**
     * Retrieves value applied by set.
     */
    public boolean isBackwards() {
        return useBackwards;
    }

    /**
     * Causes variables (those with *) to be set to all zero weights (level 1-3).
     */
    public void setDecompositionState(boolean state) {
        defaultDecomposition = state;
    }

    /**
     * Retrieves value applied by set.
     */
    public boolean isDecomposed() {
        return defaultDecomposition;
    }

    /**
     * Causes variables (those with *) to be set to all zero weights (level 1-3).
     */
    public void setAlternate(byte status) {
        defaultAlternate = status;
    }

    /**
     * Retrieves value applied by set.
     */
    public byte getAlternate() {
        return defaultAlternate;
    }

    /**
     * Sets the maximum strength level to be included in the string. 
     * E.g. with 3, only weights of 1, 2, and 3 are included: level 4 weights are discarded.
     */
    public void setStrength(int inStrength) {
        strength = inStrength;
    }

    /**
     * Retrieves value applied by set.
     */
    public int getStrength() {
        return strength;
    }

    /**
     * Retrieves version
     */
    public String getCodeVersion() {
        return codeVersion;
    }

    /**
     * Retrieves versions
     */
    public String getDataVersion() {
        return dataVersion;
    }

    /**
     * Retrieves versions
     */
    public String getUCDVersion() {
        return ucdVersion;
    }

    public static String codePointOrder(CharSequence s) {
        return appendInCodePointOrder(s, new StringBuffer()).toString();
    }

    /**
     * Appends UTF-16 string
     * with the values swapped around so that they compare in
     * code-point order. Replace 0000 and 0001 by 0001 0001/2
     * @param source Normal UTF-16 (Java) string
     * @return sort key (as string)
     * @author Markus Scherer (cast into Java by MD)
     * NOTE: changed to be longer, but handle isolated surrogates
     */
    public static StringBuffer appendInCodePointOrder(CharSequence source, StringBuffer target) {
        int cp;
        for (int i = 0; i < source.length(); i += UTF16.getCharCount(cp)) {
            cp = UTF16.charAt(source, i);
            target.append((char)((cp >> 15) | 0x8000));
            target.append((char)(cp | 0x8000));
            /*
            if (ch <= 1) { // hack to avoid nulls
                target.append('\u0001');
                target.append((char)(ch+1));
            }
            target.append((char)(ch + utf16CodePointOrder[ch>>11]));
             */
        }
        return target;
    }

    /**
     * Gets all collation elements for the input string and appends them to the output stack.
     *
     * @param sourceString input string
     * @param decomposition if true then the string is NFD'ed,
     *      otherwise it must already be normalized
     * @param output IntStack gets the collation elements appended
     */
    public void getCEs(String sourceString, boolean decomposition, IntStack output) {
        setSourceString(sourceString, decomposition);

        CEList ces;
        while ((ces = nextCEs()) != null) {
            ces.appendNonZeroTo(output);
        }
    }

    /**
     * Gets all collation elements for the input string and writes them to the output array.
     *
     * @param sourceString input string
     * @param decomposition if true then the string is NFD'ed,
     *      otherwise it must already be normalized
     * @param output array where the collation elements are written
     * @return number of collation element integers written to the output
     * @throws IndexOutOfBoundsException if the output array is too short
     */
    public int getCEs(String sourceString, boolean decomposition, int[] output) {
        setSourceString(sourceString, decomposition);
        int outpos = 0;
        output[0] = 0; // just in case!!

        CEList ces;
        while ((ces = nextCEs()) != null) {
            outpos = ces.appendNonZeroTo(output, outpos);
        }
        return outpos;
    }

    /**
     * Gets all collation elements for the input string and returns them as a CEList.
     *
     * @param sourceString input string
     * @param decomposition if true then the string is NFD'ed,
     *      otherwise it must already be normalized
     * @return a CEList with the collation elements
     */
    public CEList getCEList(String sourceString, boolean decomposition) {
        setSourceString(sourceString, decomposition);

        // If there is only one CEList, then return that one.
        CEList ces1 = nextCEs();
        if (ces1 == null) {
            return null;  // not even one (should only happen for an empty sourceString)
        }
        CEList ces = nextCEs();
        if (ces == null) {
            // only one CEList
            if (ces1.isCompletelyIgnorable()) {
                return CEList.EMPTY;
            }
            if (!ces1.containsZero()) {
                return ces1;
            }
            // Weird: ces1 contains both zero and non-zero collation elements.
            // Collect and return only the non-zero ones.
        }

        // Otherwise concatenate all lists into a new one.
        IntStack stack = new IntStack(30);
        ces1.appendNonZeroTo(stack);
        do {
            ces.appendNonZeroTo(stack);
        } while ((ces = nextCEs()) != null);
        return stack.isEmpty() ? CEList.EMPTY : new CEList(stack);
    }

    /**
     * Returns true if there is an explicit mapping for ch, or one that starts with ch.
     */
    public boolean codePointHasExplicitMappings(int ch) {
        return ucaData.codePointHasExplicitMappings(ch);
    }

    /**
     * Returns the primary weight from a 32-bit CE.
     * The primary is 16 bits, stored in b31..b16.
     *
     * @deprecated use {@link CEList#getPrimary(int)}
     */
    public static char getPrimary(int ce) {
        return CEList.getPrimary(ce);
    }

    /**
     * Returns the secondary weight from a 32-bit CE.
     * The secondary is 9 bits, stored in b15..b7.
     *
     * @deprecated use {@link CEList#getSecondary(int)}
     */
    public static char getSecondary(int ce) {
        return CEList.getSecondary(ce);
    }

    /**
     * Returns the tertiary weight from a 32-bit CE.
     * The tertiary is 7 bits, stored in b6..b0.
     *
     * @deprecated use {@link CEList#getTertiary(int)}
     */
    public static char getTertiary(int ce) {
        return CEList.getTertiary(ce);
    }

    /**
     * Utility, used to determine whether a CE is variable or not.
     */

    public boolean isVariable(int ce) {
        return (variableLowCE <= ce && ce <= variableHighCE);
    }

    /**
     * Utility, used to determine whether a CE is variable or not.
     */

    public int getVariableLowCE() {
        return variableLowCE;
    }

    /**
     * Utility, used to determine whether a CE is variable or not.
     */

    public int getVariableHighCE() {
        return variableHighCE;
    }

    /**
     * Utility, used to make a CE from the pieces. They must already
     * be in the right range of values.
     */
    public static int makeKey(int primary, int secondary, int tertiary) {
        return (primary << 16) | (secondary << 7) | tertiary;
    }

    // =============================================================
    // Utility methods
    // =============================================================

    static public String toString(String sortKey) {
        return toString(sortKey, Integer.MAX_VALUE);
    }
    /**
     * Produces a human-readable string for a sort key.
     * The 0000 separator is replaced by a '|'
     */
    static public String toString(String sortKey, int level) {
        StringBuffer result = new StringBuffer();
        boolean needSep = false;
        result.append("[");
        for (int i = 0; i < sortKey.length(); ++i) {
            char ch = sortKey.charAt(i);
            if (ch == 0) {
                if (needSep) result.append(" ");
                result.append("|");
                if (--level <= 0) {
                    break;
                }
                needSep = true;
            } else {
                if (needSep) result.append(" ");
                result.append(Utility.hex(ch));
                needSep = true;
            }
        }
        result.append("]");
        return result.toString();
    }

    static final int variableBottom = UCA.getPrimary(CE_FFFE)+1;
    
    /**
     * Produces a human-readable string for a sort key.
     * @param variableTop TODO
     * @param extraComment 
     */
    static public String toStringUCA(String sortKey, String original, int variableTop, StringBuilder extraComment) {
        extraComment.setLength(0);
        int primaryEnd = sortKey.indexOf(0);
        int secondaryEnd = sortKey.indexOf(0, primaryEnd+1);
        int tertiaryEnd = sortKey.indexOf(0, secondaryEnd+1);
        if (tertiaryEnd < 0) {
            tertiaryEnd = sortKey.length();
        }
        String primary = sortKey.substring(0, primaryEnd);
        String secondary = sortKey.substring(primaryEnd+1, secondaryEnd);
        String tertiary = sortKey.substring(secondaryEnd+1, tertiaryEnd);

        int max = Math.max(primary.length(), Math.max(secondary.length(), tertiary.length()));

        String quad = max == 1 ? original : toD.normalize(original);

        StringBuffer result = new StringBuffer();
        int qPos = 0;
        int lastQ = 0;
        boolean lastWasVariable = false;
        
        for (int i = 0; i < max; ++i) {
            char p = i < primary.length() ? primary.charAt(i) : 0;
            char s = i < secondary.length() ? secondary.charAt(i) : p != 0 ? '\u0020' : 0;
            char t = i < tertiary.length() ? tertiary.charAt(i) : s != 0 ? '\u0002' : 0;
            int q = lastQ = t == 0 ? 0 : qPos < quad.length() ? quad.codePointAt(qPos) : lastQ;
            qPos += Character.charCount(q);
            boolean isVariable = isVariablePrimary(p, variableTop, lastWasVariable);
            lastWasVariable = isVariable;

            result
            .append("[")
            .append(isVariable ? "*" : ".").append(Utility.hex(p))
            .append(".").append(Utility.hex(s))
            .append(".").append(Utility.hex(t))
            .append(".").append(Utility.hex(q))
            .append("]");
        }
        boolean noncanonical = !toD.isNormalized(original);
        boolean compatibility = !noncanonical && !toKD.isNormalized(original);

        int ceCount = max;
        if (ceCount > 1) {
            if (compatibility) {
                extraComment.append("; QQKN");
            } else {
                extraComment.append("; QCCM");
            }
        } else {
            if (compatibility) {
                extraComment.append("; QQK");
            } else if (noncanonical) {
                extraComment.append("; QQC");
            }
        }
        return result.toString();
    }

    public static boolean isVariablePrimary(char primary, int variableTop,
            boolean lastWasVariable) {
        return primary == 0 ? lastWasVariable :
            primary <= variableTop
            && variableBottom <= primary;
    }

    public static String toStringUCA(CEList ceList, String value, int variableTop, StringBuilder extraComment) {
        if (ceList.isEmpty()) {
            return "[.0000.0000.0000.0000]";
        }
        extraComment.setLength(0);
        boolean lastWasVariable = false;
        StringBuffer result = new StringBuffer();
        String quad = ceList.length() == 1 ? value : toD.normalize(value);
        int qIndex = 0;

        for (int i = 0; i < ceList.length(); ++i) {
            int ce = ceList.at(i);
            char p = UCA.getPrimary(ce);
            char s = UCA.getSecondary(ce);
            char t = UCA.getTertiary(ce);
            int q = quad.codePointAt(qIndex);
            int delta = Character.charCount(q);
            if (qIndex + delta < quad.length()) {
                qIndex += delta;
            }

            boolean isVariable = isVariablePrimary(p, variableTop, lastWasVariable);
            
            lastWasVariable = isVariable;

            result
            .append("[")
            .append(isVariable ? "*" : ".").append(Utility.hex(p))
            .append(".").append(Utility.hex(s))
            .append(".").append(Utility.hex(t))
            .append(".").append(Utility.hex(q))
            .append("]");
        }
        return result.toString();
    }


    /**
     * Produces a human-readable string for a collation element.
     * value is terminated by -1!
     */
    /*
    static public String ceToString(int[] ces, int len) {
        StringBuffer result = new StringBuffer();
        for (int i = 0; i < len; ++i) {
            result.append(ceToString(ces[i]));
        }
        return result.toString();
    }
    &/

    /**
     * Produces a human-readable string for a collation element.
     * value is terminated by -1!
     */
    /*
    static public String ceToString(int[] ces) {
        StringBuffer result = new StringBuffer();
        for (int i = 0; ; ++i) {
            if (ces[i] == TERMINATOR) break;
            result.append(ceToString(ces[i]));
        }
        return result.toString();
    }
     */

    static boolean isImplicitLeadCE(int ce) {
        return isImplicitLeadPrimary(getPrimary(ce));
    }

    static boolean isImplicitLeadPrimary(int primary) {
        return primary >= UNSUPPORTED_BASE && primary < UNSUPPORTED_LIMIT;
    }

    static boolean isImplicitPrimary(int primary) {
        return primary > 0x7FFF || isImplicitLeadPrimary(primary);
    }


    /*
The formula from the UCA:

BASE:

FB40 CJK Ideograph 
FB80 CJK Ideograph Extension A/B 
FBC0 Any other code point 

AAAA = BASE + (CP >> 15);
BBBB = (CP & 0x7FFF) | 0x8000;The mapping given to CP is then given by:

CP => [.AAAA.0020.0002.][.BBBB.0000.0000.]
     */		

    /**
     * Returns implicit value
     */

    public static void CodepointToImplicit(int cp, int[] output) {
        int base = UNSUPPORTED_OTHER_BASE;
        if (UCD.isCJK_BASE(cp)) base = UNSUPPORTED_CJK_BASE;
        else if (UCD.isCJK_AB(cp)) base = UNSUPPORTED_CJK_AB_BASE;
        output[0] = base + (cp >>> 15);
        output[1] = (cp & 0x7FFF) | 0x8000;
    }

    public static void UnassignedToImplicit(int cp, int[] output) {
        int base = UNSUPPORTED_OTHER_BASE;
        output[0] = base + (cp >>> 15);
        output[1] = (cp & 0x7FFF) | 0x8000;
    }

    /**
     * Takes implicit value
     */

    static int ImplicitToCodePoint(int leadImplicit, int trailImplicit) {
        // could probably optimize all this, but it is not worth it.
        if (leadImplicit < UNSUPPORTED_BASE || leadImplicit >= UNSUPPORTED_LIMIT) {
            throw new IllegalArgumentException("Lead implicit out of bounds: " + Utility.hex(leadImplicit));
        }
        if ((trailImplicit & 0x8000) == 0) {
            throw new IllegalArgumentException("Trail implicit out of bounds: " + Utility.hex(trailImplicit));
        }
        int base;
        if (leadImplicit >= UNSUPPORTED_OTHER_BASE) base = UNSUPPORTED_OTHER_BASE;
        else if (leadImplicit >= UNSUPPORTED_CJK_AB_BASE) base = UNSUPPORTED_CJK_AB_BASE;
        else base = UNSUPPORTED_CJK_BASE;

        int result = ((leadImplicit - base) << 15) | (trailImplicit & 0x7FFF);

        if (result > 0x10FFFF) {
            throw new IllegalArgumentException("Resulting character out of  bounds: "
                    + Utility.hex(leadImplicit) + ", " + Utility.hex(trailImplicit) 
                    + " => " + result);
        }
        return result;
    }

    /**
     * Supplies a zero-padded hex representation of an integer (without 0x)
     */
    /*
    static public String hex(int i) {
        String result = Long.toString(i & 0xFFFFFFFFL, 16).toUpperCase();
        return "00000000".substring(result.length(),8) + result;
    }
     */
    /**
     * Supplies a zero-padded hex representation of a Unicode character (without 0x, \\u)
     */
    /*
    static public String hex(char i) {
        String result = Integer.toString(i, 16).toUpperCase();
        return "0000".substring(result.length(),4) + result;
    }
     */
    /**
     * Supplies a zero-padded hex representation of a Unicode character (without 0x, \\u)
     */
    /*
    static public String hex(byte b) {
        int i = b & 0xFF;
        String result = Integer.toString(i, 16).toUpperCase();
        return "00".substring(result.length(),2) + result;
    }
     */
    /**
     * Supplies a zero-padded hex representation of a Unicode String (without 0x, \\u)
     *@param sep can be used to give a sequence, e.g. hex("ab", ",") gives "0061,0062"
     */
    /*
    static public String hex(String s, String sep) {
        StringBuffer result = new StringBuffer();
        for (int i = 0; i < s.length(); ++i) {
            if (i != 0) result.append(sep);
            result.append(hex(s.charAt(i)));
        }
        return result.toString();
    }
     */
    /**
     * Supplies a zero-padded hex representation of a Unicode String (without 0x, \\u)
     *@param sep can be used to give a sequence, e.g. hex("ab", ",") gives "0061,0062"
     */
    /*
    static public String hex(StringBuffer s, String sep) {
        StringBuffer result = new StringBuffer();
        for (int i = 0; i < s.length(); ++i) {
            if (i != 0) result.append(sep);
            result.append(hex(s.charAt(i)));
        }
        return result.toString();
    }
     */

    // =============================================================
    // Privates
    // =============================================================


    /**
     * Array used to reorder surrogates to top of 16-bit range, and others down.
     * Adds 2000 to D800..DFFF, making them F800..FFFF
     * Subtracts 800 from E000..FFFF, making them D800..F7FF
     */
    /* This is for alternate code in appendInCodePointOrder()
    private static final int[] utf16CodePointOrder = {
        0, 0, 0, 0,                        // 00, 08, 10, 18
        0, 0, 0, 0,                        // 20, 28, 30, 38
        0, 0, 0, 0,                        // 40, 48, 50, 58
        0, 0, 0, 0,                        // 60, 68, 70, 78
        0, 0, 0, 0,                        // 80, 88, 90, 98
        0, 0, 0, 0,                        // A0, A8, B0, B8
        0, 0, 0, 0x2000,                   // C0, C8, D0, D8
        -0x800, -0x800, -0x800, -0x800     // E0, E8, F0, F8
    };
    */

    /**
     * NFD required
     */
    private static Normalizer toD;
    private static Normalizer toKD;

    /**
     * Records the dataversion
     */
    public static final String BADVERSION = "Missing @version in data!!";
    private String dataVersion = BADVERSION;

    /**
     * Records the dataversion
     */
    private String ucdVersion = "?";

    /**
     * Turns backwards (e.g. for French) on globally for all secondaries
     */
    private boolean useBackwards = false;

    /**
     * Choice of how to handle variables (those with *)
     */
    private byte defaultAlternate = SHIFTED;

    /**
     * For testing
     */
    private boolean defaultDecomposition = true;

    /**
     * Sets the maximum strength level to be included in the string. 
     * E.g. with 3, only weights of 1, 2, and 3 are included: level 4 weights are discarded.
     */
    private int strength = 4;

    /**
     * Position in decompositionBuffer used when constructing sort key
     */
    private int[] index = new int[1];

    /**
     * List of files to use for constructing the CE data, used by build()
     */

    /*    private static final String[] KEYS = {
        //"D:\\UnicodeData\\testkeys.txt",
        BASE_DIR + "UCA\\allkeys" + VERSION + ".txt",

        BASE_DIR + "UnicodeData\\Collation\\basekeys" + VERSION + ".txt",
        BASE_DIR + "UnicodeData\\Collation\\compkeys" + VERSION + ".txt",
        BASE_DIR + "UnicodeData\\Collation\\ctrckeys" + VERSION + ".txt",

    };
     */ 
    /**
     * File buffer size, used to make reads faster.
     */
    private static final int BUFFER_SIZE = 64*1024;

    // =============================================================
    // Collation Element Memory Data Table Formats
    // =============================================================

    /**
     * Temporary buffer used in getSortKey for the decomposed string
     */
    private StringBuffer decompositionBuffer = new StringBuffer();

    // was 0xFFC20101;

    /**
     * We take advantage of the variables being in a closed range to save a bit per CE.
     * The low and high values are initially set to be at the opposite ends of the range,
     * as the table is built from the UCA data, they are narrowed in.
     * The first three values are used in building; the last two in testing.
     */
    private int variableLowCE;  // used for testing against
    private int variableHighCE; // used for testing against

    /**
     * Marks whether we are using the full data set, or an abbreviated version for
     * an applet.
     */

    private boolean fullData;

    // =============================================================
    // Temporaries used in getCE. 
    // Made part of the object to avoid reallocating each time.
    // =============================================================

    /**
     * Temporary buffers used in getSortKey to store weights.
     * These are NOT strings of Unicode characters--they are
     * lists of weights. But this is a convenient way to store them,
     * since Java doesn't have unsigned shorts.
     */
    private StringBuilder primaries = new StringBuilder(100);
    private StringBuilder secondaries = new StringBuilder(100);
    private StringBuilder tertiaries = new StringBuilder(100);
    private StringBuilder quaternaries = new StringBuilder(100);

    /**
     * Temporary buffer used to collect progress data for debugging
     */
    StringBuffer debugList = new StringBuffer(100);

    private StringBuffer hangulBuffer = new StringBuffer();

    private int[] implicit = new int[2];

    /**
     * Returns the collation elements for the character or substring
     * of the decomposition buffer starting at the index.
     * Advances the index past that.
     * Returns null at the end of the input.
     */
    private CEList nextCEs() {
        if (index[0] >= decompositionBuffer.length()) {
            return null;
        }
        CEList ces = ucaData.fetchCEs(decompositionBuffer, index);
        if (ces != null) {
            return ces;
        }

        int i = index[0];
        int c = decompositionBuffer.codePointAt(i);
        if (UCD.isHangulSyllable(c)) {
            hangulBuffer.setLength(0);
            decomposeHangul(c, hangulBuffer);
            decompositionBuffer.replace(i, i + 1, hangulBuffer.toString());
            return nextCEs();
        }

        index[0] = i + Character.charCount(c);
        CodepointToImplicit(c, implicit);
        implicit[0] = makeKey(implicit[0], NEUTRAL_SECONDARY, NEUTRAL_TERTIARY);
        implicit[1] = makeKey(implicit[1], 0, 0);
        return new CEList(implicit);
    }

    /**
     * Constants for Hangul
     */
    static final int // constants
    SBase = 0xAC00, LBase = 0x1100, VBase = 0x1161, TBase = 0x11A7,
    LCount = 19, VCount = 21, TCount = 28,
    NCount = VCount * TCount,   // 588
    SCount = LCount * NCount,   // 11172
    LastInitial = LBase + LCount-1, // last initial jamo
    LastPrimary = SBase + (LCount-1) * VCount * TCount; // last corresponding primary

    public static StringBuffer decomposeHangul(int s, StringBuffer result) {
        int SIndex = s - SBase;
        if (0 > SIndex || SIndex >= SCount) {
            throw new IllegalArgumentException("Non-Hangul Syllable");
        }
        int L = LBase + SIndex / NCount;
        int V = VBase + (SIndex % NCount) / TCount;
        int T = TBase + SIndex % TCount;
        result.append((char)L);
        result.append((char)V);
        if (T != TBase) result.append((char)T);
        return result;
    }

    /**
     * Fix for Hangul, since the tables are not set up right.
     * The fix for Hangul is to give different values to the combining initial 
     * Jamo to put them up into the AC00 range, as follows. Each one is put
     * after the first syllable it begins.
     *
    private int fixJamo(char ch, int jamoCe) {

        int result = jamoCe - hangulHackBottom + 0xAC000000; // put into right range
        if (DEBUG) System.out.println("\tChanging " + hex(ch) + " " + hex(jamoCe) + " => " + hex(result));
        return result;
        /*
        int newPrimary;
        int LIndex = jamo - LBase;
        if (LIndex < LCount) {
            newPrimary = SBase + (LIndex + 1) * VCount * TCount; // multiply to match syllables
        } else {
            newPrimary = LastPrimary + (jamo - LastInitial); // just shift up
        }
        return makeKey(newPrimary, 0x21, 0x2);  // make secondary difference!
     * /
    }
     */

    // =============================================================
    // Building Collation Element Tables
    // =============================================================

    /**
     * Value for returning int as well as function return,
     * since Java doesn't have output parameters
     */
    private int[] position = new int[1]; 


    /*public Hashtable getContracting() {
        return new Hashtable(multiTable);
    }
     */


    public UCAContents getContents(Normalizer skipDecomps) {
        return new UCAContents(skipDecomps, ucdVersion);
    }


    private static final UnicodeSet moreSamples = new UnicodeSet();
    static {
        moreSamples.add("\u09C7\u09BE");
        moreSamples.add("\u09C7\u09D7");
        moreSamples.add("\u1025\u102E");
        moreSamples.add("\u0DD9\u0DCF");
        moreSamples.add("\u0DD9\u0DDF");
        moreSamples.add("\u1100\u1161");
        moreSamples.add("\u1100\u1175");
        moreSamples.add("\u1112\u1161");
        moreSamples.add("\u1112\u1175");
        moreSamples.add("\uAC00\u1161");
        moreSamples.add("\uAC00\u1175");
        moreSamples.add("\uD788\u1161");
        moreSamples.add("\uD788\u1175");
    }

    // static UnicodeSet homelessSecondaries = new UnicodeSet(0x0176, 0x0198);
    //  0x0153..0x017F


    public class UCAContents {
        private Iterator<Map.Entry<String, CEList>> iter = ucaData.getSortedMappings().entrySet().iterator();
        private CEList ces;
        private Normalizer skipDecomps;
        private Normalizer nfkd;
        private int currentRange = SAMPLE_RANGES.length; // set to ZERO to enable
        private int startOfRange = SAMPLE_RANGES[0][0];
        private int endOfRange = startOfRange;
        private int itemInRange = startOfRange;
        private boolean doSamples = false;
        private AbbreviatedUnicodeSetIterator usi = new AbbreviatedUnicodeSetIterator();
        private UnicodeSetIterator moreSampleIterator = new UnicodeSetIterator(moreSamples);

        UCAContents(Normalizer skipDecomps, String unicodeVersion) {
            this.nfkd = new Normalizer(Normalizer.NFKD, unicodeVersion);
            this.skipDecomps = skipDecomps;
            currentRange = 0;
            usi.reset(getStatistics().unspecified, true);
            //usi.setAbbreviated(true);

            // FIX SAMPLES
            if (SAMPLE_RANGES[0][0] == 0) {
                for (int i = 0; ; ++i) { // add first unallocated character
                    if (!ucd.isAssigned(i)) {
                        SAMPLE_RANGES[0][0] = i;
                        break;
                    }
                }
            }
        }

        public void setDoEnableSamples(boolean newValue) {
            doSamples = newValue;
        }

        /**
         * returns a string
         */
        public String next() {
            String result = null; // null if done
            ces = null;

            // normal case
            while (iter.hasNext()) {
                Map.Entry<String, CEList> entry = iter.next();
                result = entry.getKey();
                if (UTF16Plus.isSingleCodePoint(result)) {
                    int c = result.codePointAt(0);
                    if (skipDecomps != null && !skipDecomps.isNormalized(c)) {  // CHECK THIS
                        result = null;
                        continue;
                    }
                    if (!getStatistics().haveUnspecified) {
                        getStatistics().unspecified.add(c);
                    }
                }
                ces = entry.getValue();
                return result;
            }

            // Update statistics once after all mappings have been enumerated.
            if (!getStatistics().haveUnspecified) {
                if (DEBUG) System.out.println("Specified = " + getStatistics().unspecified.toPattern(true));
                UnicodeSet temp = new UnicodeSet();
                for (int i = 0; i <= 0x10ffff; ++i) {
                    if (!ucd.isAllocated(i)) continue;
                    if (!getStatistics().unspecified.contains(i)) {
                        temp.add(i);
                    }

                    // add the following so that if a CJK is in a decomposition, we add it
                    if (!nfkd.isNormalized(i)) {
                        String decomp = nfkd.normalize(i);
                        int cp2;
                        for (int j = 0; j < decomp.length(); j += UTF16.getCharCount(cp2)) {
                            cp2 = UTF16.charAt(decomp, j);
                            if (!getStatistics().unspecified.contains(cp2)) {
                                temp.add(cp2);
                            }
                        }
                    }
                }
                getStatistics().unspecified = temp;
                usi.reset(getStatistics().unspecified, true);
                //usi.setAbbreviated(true);
                if (DEBUG) System.out.println("Unspecified = " + getStatistics().unspecified.toPattern(true));
                getStatistics().haveUnspecified = true;
            }

            if (!doSamples) return null;

            if (usi.next()) {
                if (usi.codepoint == usi.IS_STRING) result = usi.string;
                else result = UTF16.valueOf(usi.codepoint);
                if (DEBUG) System.out.println("Unspecified: " + ucd.getCodeAndName(result));
                ces = getCEList(result, true);
                return result;
            }

            if (moreSampleIterator.next()) {
                result = moreSampleIterator.getString();
                if (DEBUG) System.out.println("More Samples: " + ucd.getCodeAndName(result));
                ces = getCEList(result, true);
                return result;
            }

            // extra samples
            if (currentRange < SAMPLE_RANGES.length) {
                try {
                    result = UTF16.valueOf(itemInRange);
                } catch (RuntimeException e) {
                    System.out.println(Utility.hex(itemInRange));
                    throw e;
                }
                ++itemInRange;
                if (itemInRange > endOfRange) {
                    ++currentRange;
                    if (currentRange < SAMPLE_RANGES.length) {
                        startOfRange = itemInRange = SAMPLE_RANGES[currentRange][0];
                        endOfRange = SAMPLE_RANGES[currentRange].length > 1
                        ? SAMPLE_RANGES[currentRange][1]
                                                      : startOfRange;
                        //skip = ((endOfRange - startOfRange) / 3);
                    }
                } else if (itemInRange > startOfRange + 5 && itemInRange < endOfRange - 5 /* - skip*/) {
                    //itemInRange += skip;
                    itemInRange = endOfRange - 5;
                }
                ces = getCEList(result, true);
                return result;
            }

            return null;
        }

        /**
         * Returns the CEs for the string that was returned by the last call to next().
         */
        public CEList getCEs() {
            return ces;
        }

        /**
         * @return Returns the doSamples.
         */
        public boolean isDoSamples() {
            return doSamples;
        }
    }

    static final int[][] SAMPLE_RANGES = {
        {0}, // LEAVE EMPTY--Turns into first unassigned character
        {0xFFF0}, 
        {0xD800},
        {0xDC00},
        {0xDFFF},
        {0xFFFE},
        {0xFFFF},
        {0x10000},
        {0xC0000},
        {0xD0000},
        {0x10FFFF},
        {0x10FFFE},
        {0x10FFFF},
        {UCD_Types.CJK_A_BASE, UCD_Types.CJK_A_LIMIT},
        {UCD_Types.CJK_BASE, UCD_Types.CJK_LIMIT},
        {0xAC00, 0xD7A3},
        {0xA000, 0xA48C},
        {0xE000, 0xF8FF},
        {UCD_Types.CJK_B_BASE, UCD_Types.CJK_B_LIMIT},
        {UCD_Types.CJK_C_BASE, UCD_Types.CJK_C_LIMIT},
        {UCD_Types.CJK_D_BASE, UCD_Types.CJK_D_LIMIT},
        {0xE0000, 0xE007E},
        {0xF0000, 0xF00FD},
        {0xFFF00, 0xFFFFD},
        {0x100000, 0x1000FD},
        {0x10FF00, 0x10FFFD},
    };

    /**
     * Adds the collation elements from a file (or other stream) in the UCA format.
     * Values will override any previous mappings.
     */
    private void addCollationElements(BufferedReader in) throws java.io.IOException {
        IntStack tempStack = new IntStack(100);
        StringBuffer multiChars = new StringBuffer(); // used for contracting chars
        String inputLine = "";
        boolean[] wasImplicitLeadPrimary = new boolean[1];

        while (true) try {
            inputLine = in.readLine();
            if (inputLine == null) break;       // means file is done

            // HACK
            if (inputLine.startsWith("# Variant secondaries:")) {
                getStatistics().variantSecondaries = extractSet(inputLine);
            } else if (inputLine.startsWith("# Digit secondaries:")) {
                getStatistics().digitSecondaries = extractSet(inputLine);
            }

            String line = cleanLine(inputLine); // remove comments, extra whitespace
            if (line.length() == 0) continue;   // skip empty lines

            if (DEBUG_SHOW_LINE) {
                System.out.println("Processing: " + inputLine);
            } 

            position[0] = 0;                    // start at front of line
            if (line.startsWith("@")) {
                if (line.startsWith("@version")) {
                    dataVersion = line.substring("@version".length()+1).trim();
                    continue;
                }

                throw new IllegalArgumentException("Illegal @ command: " + line);
            }

            // collect characters
            multiChars.setLength(0);            // clear buffer

            char value = getChar(line, position);
            multiChars.append(value);

            //fixSurrogateContraction(value);
            char value2 = getChar(line, position);
            // append until we get terminator
            while (value2 != NOT_A_CHAR) {
                multiChars.append(value2);
                value2 = getChar(line, position);
            }

            int firstCodePoint = multiChars.codePointAt(0);

            if (RECORDING_CHARS) {
                getStatistics().found.addAll(multiChars.toString());
                //                if (found.contains(0x1CD0)) {
                //                  System.out.println("found char");
                //                }
            }
            if (!fullData && RECORDING_DATA) {
                if (value == 0 || value == '\t' || value == '\n' || value == '\r'
                    || (0x20 <= value && value <= 0x7F)
                    || (0x80 <= value && value <= 0xFF)
                    || (0x300 <= value && value <= 0x3FF)
                ) {
                    System.out.println("    + \"" + inputLine + "\\n\"");
                }
            }
            // for recording information
            boolean record = true;
            /* if (multiChars.length() > 0) record = false;
            else */
            if (!toD.isNormalized(value)) record = false;

            // collect CEs
            if (false && value == 0x2F00) {
                System.out.println("debug");
            }

            wasImplicitLeadPrimary[0] = false;

            int ce = getCEFromLine(firstCodePoint, line, position, record, wasImplicitLeadPrimary, true);
            int ce2 = getCEFromLine(firstCodePoint, line, position, record, wasImplicitLeadPrimary, false);
            if (CHECK_UNIQUE && (ce2 == TERMINATOR || CHECK_UNIQUE_EXPANSIONS)) {
                if (!CHECK_UNIQUE_VARIABLES) {
                    checkUnique(value, ce, 0, inputLine); // only need to check first value
                } else {
                    int key1 = ce >>> 16;
                    if (isVariable(ce)) {
                        checkUnique(value, 0, key1, inputLine); // only need to check first value
                    }
                }
            }

            tempStack.clear();
            tempStack.push(ce);

            while (ce2 != TERMINATOR) {
                tempStack.push(ce2);
                ce2 = getCEFromLine(firstCodePoint, line, position, record, wasImplicitLeadPrimary, false);
                if (ce2 == TERMINATOR) break;
            } 

            ucaData.add(multiChars, tempStack);

        } catch (RuntimeException e) {
            System.out.println("Error on line: " + inputLine);
            throw e;
        }
    }

    public void overrideCE(String multiChars, IntStack tempStack) {
        ucaData.add(multiChars, tempStack);
    }

    public void overrideCE(String multiChars, int ce) {
        IntStack tempStack = new IntStack(1);
        tempStack.push(ce);
        ucaData.add(multiChars, tempStack);
    }

    public void overrideCE(String multiChars, int primary, int secondary, int tertiary) {
        IntStack tempStack = new IntStack(1);
        int ce = UCA.makeKey(primary, secondary, tertiary);
        tempStack.push(ce);
        ucaData.add(multiChars, tempStack);
    }

    /**
     * 
     */
    private UnicodeSet extractSet(String inputLine) {
        //# Variant secondaries:    0177..017B (5)
        //# Digit secondaries:      017C..0198 (29)
        Matcher m = Pattern.compile(".*:\\s*([0-9A-Fa-f]+)\\.\\.([0-9A-Fa-f]+).*").matcher("");
        if (!m.reset(inputLine).matches()) throw new IllegalArgumentException("Failed to recognized special Ken lines: " + inputLine);
        return new UnicodeSet(Integer.parseInt(m.group(1),16), Integer.parseInt(m.group(2),16));
    }

    /*
    private void concat(int[] ces1, int[] ces2) {

    }
     */
    
    public Iterator<String> getContractions() {
        return ucaData.getContractions();
    }

    /**
     * Checks the internal tables corresponding to the UCA data.
     */
    private void cleanup() {
        ucaData.checkConsistency();

        /* TODO(Markus):
         * - Stop checking for all code unit prefixes (this is the code commented out here).
         * - Instead, start checking that for every contraction that ends with a non-zero ccc
         *   there is a mapping for the one-code-point-shorter contraction.
        Map missingStrings = new HashMap();
        Map tempMap = new HashMap();

        Iterator enum1 = ucaData.getContractions();
        while (enum1.hasNext()) {
            String sequence = (String)enum1.next();
            //System.out.println("Contraction: " + Utility.hex(sequence));
            for (int i = sequence.length()-1; i > 0; --i) {
                String shorter = sequence.substring(0,i);
                if (!ucaData.contractionTableContains(shorter)) {
                    IntStack tempStack = new IntStack(1);
                    getCEs(shorter, true, tempStack);
                    if (false) System.out.println("WARNING: CLOSING: " + ucd.getCodeAndName(shorter)
                            + " => " + CEList.toString(tempStack));
                    tempMap.put(shorter, tempStack);
                    // missingStrings.put(shorter,"");
                    // collationElements[sequence.charAt(0)] = UNSUPPORTED; // nuke all bad values
                }
            }
        }

        // now add them. We couldn't before because we were iterating over it.

        enum1 = tempMap.keySet().iterator();
        while (enum1.hasNext()) {
            String shorter = (String) enum1.next();
            IntStack tempStack = (IntStack) tempMap.get(shorter);
            ucaData.add(shorter, tempStack);
        }


        enum1 = missingStrings.keySet().iterator();
        if (missingStrings.size() != 0) {
            String errorMessage = "";
            while (enum1.hasNext()) {
                String missing = (String)enum1.next();
                if (errorMessage.length() != 0) errorMessage += ", ";
                errorMessage += "\"" + missing + "\"";
            }
            throw new IllegalArgumentException("Contracting table not closed! Missing " + errorMessage);
        }
         */

        //fixlater;
        variableLowCE = makeKey(1,0,0);
        variableHighCE = makeKey(ucaData.variableHigh, CEList.SECONDARY_MAX, CEList.TERTIARY_MAX); // turn on bottom bits

        //int hangulHackBottom;
        //int hangulHackTop;

        //hangulHackBottom = collationElements[0x1100] & 0xFFFF0000; // remove secondaries & tertiaries
        //hangulHackTop = collationElements[0x11F9] | 0xFFFF; // bump up secondaries and tertiaries
        //if (SHOW_STATS) System.out.println("\tHangul Hack: " + Utility.hex(hangulHackBottom) + ", " + Utility.hex(hangulHackTop));

        // show some statistics
        if (SHOW_STATS) System.out.println("\tcount1: " + getStatistics().count1);
        if (SHOW_STATS) System.out.println("\tcount2: " + getStatistics().max2);
        if (SHOW_STATS) System.out.println("\tcount3: " + getStatistics().max3);
        if (SHOW_STATS) System.out.println("\tcontractions: " + ucaData.getContractionCount());

        if (SHOW_STATS) System.out.println("\tMIN1/MAX1: " + Utility.hex(getStatistics().MIN1) + "/" + Utility.hex(getStatistics().MAX1));
        if (SHOW_STATS) System.out.println("\tMIN2/MAX2: " + Utility.hex(getStatistics().MIN2) + "/" + Utility.hex(getStatistics().MAX2));
        if (SHOW_STATS) System.out.println("\tMIN3/MAX3: " + Utility.hex(getStatistics().MIN3) + "/" + Utility.hex(getStatistics().MAX3));

        if (SHOW_STATS) System.out.println("\tVar Min/Max: " + Utility.hex(ucaData.variableLow) + "/" + Utility.hex(ucaData.variableHigh));
        if (SHOW_STATS) System.out.println("\tNon-Var Min: " + Utility.hex(ucaData.nonVariableLow));

        if (SHOW_STATS) System.out.println("\trenumberedVariable: " + getStatistics().renumberedVariable);
    }

    /**
     * Remove comments, extra whitespace
     */
    private String cleanLine(String line) {
        int commentPosition = line.indexOf('#');
        if (commentPosition >= 0) line = line.substring(0,commentPosition);
        commentPosition = line.indexOf('%');
        if (commentPosition >= 0) line = line.substring(0,commentPosition);
        return line.trim();
    }

    /**
     * Get a char from a line, of form: (<space> | <comma>)* <hex>*
     *@param position on input, the place to start at. 
     * On output, updated to point to the next place to search.
     *@return the character, or NOT_A_CHAR when done
     */

    // NOTE in case of surrogates, we buffer up the second character!!
    char charBuffer = 0;

    private char getChar(String line, int[] position) {
        char ch;
        if (charBuffer != 0) {
            ch = charBuffer;
            charBuffer = 0;
            return ch;
        }
        int start = position[0];
        while (true) { // trim whitespace
            if (start >= line.length()) return NOT_A_CHAR;
            ch = line.charAt(start);
            if (ch != ' ' && ch != ',') break;
            start++;
        }
        // from above, we have at least one char
        int hexLimit = start;
        while ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F')) {
            hexLimit++;
            ch = line.charAt(hexLimit);
        }
        if (hexLimit >= start + 4) {
            position[0] = hexLimit;
            int cp = Integer.parseInt(line.substring(start,hexLimit),16);
            if (cp <= 0xFFFF) return (char)cp;
            //DEBUGCHAR = true;
            charBuffer = UTF16.getTrailSurrogate(cp);
            return UTF16.getLeadSurrogate(cp);
        }

        return NOT_A_CHAR; 
    }

    boolean DEBUGCHAR = false;    


    public CharSequence getRepresentativePrimary(int primary) {
        StringBuilder result = getStatistics().representativePrimary.get(primary);
        if (result == null) {
            result = getStatistics().representativePrimarySeconds.get(primary);
            throw new IllegalArgumentException();
        }
        return result;
    }

    public int writeUsedWeights(PrintWriter p, int strength, MessageFormat mf) {
        RoBitSet weights = (strength == 1 ? getStatistics().getPrimarySet() 
                : strength == 2 ? getStatistics().getSecondarySet() 
                        : getStatistics().getTertiarySet()); // strength == 1 ? getStatistics().primarySet : strength == 2 ? getStatistics().secondarySet : getStatistics().tertiarySet;
        int first = -1;
        int count = 0;
        for (int i = 0; i <= weights.length(); ++i) {
            if (strength > 1) {
                if (weights.get(i)) {
                    count++;
                    p.println(mf.format(new Object[] {Utility.hex((char)i), new Integer(getStatistics().stCounts[strength][i])}));
                }
                continue;
            }
            if (weights.get(i)) {
                if (first == -1) first = i;
            } else if (first != -1) {
                int last = i-1;
                int diff = last - first + 1;
                count += diff;
                String lastStr = last == first ? "" : Utility.hex((char)last);
                p.println(mf.format(new Object[] {Utility.hex((char)first),lastStr,new Integer(diff), new Integer(count)}));
                first = -1;
            }
        }
        return count;  
    }

    /**
     * Gets a CE from a UCA format line
     *@param value the first character for the line. Just used for statistics.
     *@param line a string of form "[.0000.0000.0000.0000]..."
     *@param position on input, the place to start at. 
     * On output, updated to point to the next place to search.
     */

    boolean haveVariableWarning = false;
    boolean haveZeroVariableWarning = false;

    private int getCEFromLine(int value, String line, int[] position, boolean record, boolean[] lastWasImplicitLead, boolean first) {
        int start = line.indexOf('[', position[0]);
        if (start == -1) return TERMINATOR;
        boolean variable = line.charAt(start+1) == '*';
        int key1 = Integer.parseInt(line.substring(start+2,start+6),16);
        if (key1 == TEST_PRIMARY) {
            key1 = key1; // for debugging
        }
        int key2 = Integer.parseInt(line.substring(start+7,start+11),16);
        int key3 = Integer.parseInt(line.substring(start+12,start+16),16);
        if (key1 == 0 && variable) {
            if (!haveZeroVariableWarning) {
                System.out.println("\tBAD DATA: Zero L1s cannot be variable!!: " + line);
                haveZeroVariableWarning = true;
            }
            variable = false; // FIX DATA FILE
        }
        if (key2 > CEList.SECONDARY_MAX) {
            throw new IllegalArgumentException("Weight2 doesn't fit: " + Utility.hex(key2) + "," + line);
        }
        if (key3 > CEList.TERTIARY_MAX) {
            throw new IllegalArgumentException("Weight3 doesn't fit: " + Utility.hex(key3) + "," + line);
        }
        // adjust variable bounds, if needed
        if (variable) {
            if (key1 > ucaData.nonVariableLow) {
                if (!haveVariableWarning) {
                    System.out.println("\tBAD DATA: Variable overlap, nonvariable low: "
                            + Utility.hex(ucaData.nonVariableLow) + ", line: \"" + line + "\"");
                    haveVariableWarning = true;
                }
            } else {
                if (key1 < ucaData.variableLow) {
                    ucaData.variableLow = key1;
                }
                if (ucaData.getPrimaryRemap() == null && key1 > ucaData.variableHigh) {
                    ucaData.variableHigh = key1;
                }
            }
        } else if (key1 != 0) { // not variable, not zero
            if (key1 < ucaData.variableHigh) {
                if (!haveVariableWarning) {
                    System.out.println("\tBAD DATA: Variable overlap, variable high: "
                            + Utility.hex(ucaData.variableHigh) + ", line: \"" + line + "\"");
                    haveVariableWarning = true;
                }
            } else {
                if (key1 < ucaData.nonVariableLow) ucaData.nonVariableLow = key1;
            }
        }

        position[0] = start + 17;
        /*
        if (VARIABLE && variable) {
            key1 = key2 = key3 = 0;
            if (CHECK_UNIQUE) {
                if (key1 != lastUniqueVariable) renumberedVariable++;
                result = renumberedVariable;     // push primary down
                lastUniqueVariable = key1;
                key3 = key1;
                key1 = key2 = 0;
            }
        }
         */
        return makeKey(key1, key2, key3);
    }


    /**
     * Used for checking data file integrity
     */
    private Map uniqueTable = new HashMap();

    /**
     * Used for checking data file integrity
     */
    private void checkUnique(char value, int result, int fourth, String line) {
        if (!toD.isNormalized(value)) return; // don't check decomposables.
        Object ceObj = new Long(((long)result << 16) | fourth);
        Object probe = uniqueTable.get(ceObj);
        if (probe != null) {
            System.out.println("\tCE(" + Utility.hex(value) 
                    + ")=CE(" + Utility.hex(((Character)probe).charValue()) + "); " + line);

        } else {
            uniqueTable.put(ceObj, new Character(value));
        }
    }
    /**
     * @return Returns the fileVersion.
     */
    public String getFileVersion() {
        return fileVersion;
    }
    /**
     * @return Returns the uCA_GEN_DIR.
     */
    public static String getUCA_GEN_DIR() {
        //  try {
        //    final File file = new File("org/unicode/data/gen/uca");
        //	System.out.println(file.getCanonicalPath());
        //	System.out.println(file.isDirectory());
        //    if (true) throw new IllegalArgumentException();
        //  } catch (IOException e) {
        //    throw (IllegalArgumentException) new IllegalArgumentException().initCause(e);
        //  }
        return BASE_UCA_GEN_DIR + Default.ucdVersion() + "/"; //  + getDataVersion() + "/";
    }


    /**
     * @return Returns the homelessSecondaries.
     */
    public UnicodeSet getHomelessSecondaries() {
        if (getStatistics().homelessSecondaries == null) {
            getStatistics().homelessSecondaries = new UnicodeSet(getStatistics().variantSecondaries).addAll(getStatistics().digitSecondaries);
        }
        return getStatistics().homelessSecondaries;
    }

    public static UCA buildCollator(Remap primaryRemap) {
        try {
            System.out.println("Building UCA");
            String file = Utility.searchDirectory(new File(UCD_Types.BASE_DIR + "uca/" + Default.ucdVersion() + "/"), "allkeys", true, ".txt");
            UCA collator = new UCA(file, Default.ucdVersion(), primaryRemap);
            System.out.println("Built version " + collator.getDataVersion() + "/ucd: " + collator.getUCDVersion());
            System.out.println("Building UCD data");
            return collator;
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    UCA_Statistics getStatistics() {
        return ucaData.statistics;
    }

    public static final class Remap {
        private int counter = 0x100;
        private Map<Integer,Integer> primaryRemap = new TreeMap<Integer,Integer>();
        private Map<Integer,IntStack> characterRemap = new TreeMap<Integer,IntStack>();
        private int variableHigh;
        private int firstDucetNonVariable;

        public Integer getRemappedPrimary(int ducetPrimary) {
            return primaryRemap.get(ducetPrimary);
        }
        public IntStack getRemappedCharacter(int source) {
            return characterRemap.get(source);
        }
        /**
         * The UnicodeSet is not characters but primaries....
         * @param items
         * @return
         */
        public Remap addItems(UnicodeSet items) {
            for (String s : items) {
                primaryRemap.put(s.codePointAt(0), counter++);
            }
            return this;
        }
        public Remap putRemappedCharacters(int codePoint) {
            IntStack stack = new IntStack(1);
            stack.append(makeKey(counter++, NEUTRAL_SECONDARY, NEUTRAL_TERTIARY));
            characterRemap.put(codePoint, stack);
            //*130D.0020.0002       
            return this;
        }
        public Map<Integer, IntStack> getCharacterRemap() {
            return Collections.unmodifiableMap(characterRemap);
        }
        public void setFirstDucetNonVariable(int firstDucetNonVariable) {
            this.firstDucetNonVariable = primaryRemap.get(firstDucetNonVariable);
        }
        public int getFirstDucetNonVariable() {
            return firstDucetNonVariable;
        }
        public int getVariableHigh() {
            return variableHigh;
        }
        public Remap setVariableHigh() {
            variableHigh = counter-1;
            return this;
        }
    }

}

