package org.datavec.api.records.reader.impl.misc;

import lombok.extern.slf4j.Slf4j;
import org.datavec.api.records.Record;
import org.datavec.api.records.metadata.RecordMetaData;
import org.datavec.api.records.metadata.RecordMetaDataLine;
import org.datavec.api.records.reader.impl.LineRecordReader;
import org.datavec.api.writable.DoubleWritable;
import org.datavec.api.split.InputSplit;
import org.datavec.api.writable.IntWritable;
import org.datavec.api.writable.Writable;
import org.datavec.api.conf.Configuration;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Record reader for SVMLight format, which can generally
 * be described as
 *
 * LABEL INDEX:VALUE INDEX:VALUE ...
 *
 * SVMLight format is well-suited to sparse data (e.g.,
 * bag-of-words) because it omits all features with value
 * zero.
 *
 * We support an "extended" version that allows for multiple
 * targets (or labels) separated by a comma, as follows:
 *
 * LABEL1,LABEL2,... INDEX:VALUE INDEX:VALUE ...
 *
 * This can be used to represent either multitask problems or
 * multilabel problems with sparse binary labels (controlled
 * via the "MULTILABEL" configuration option).
 *
 * Like scikit-learn, we support both zero-based and one-based indexing.
 *
 * Further details on the format can be found at
 * - http://svmlight.joachims.org/
 * - http://www.csie.ntu.edu.tw/~cjlin/libsvmtools/datasets/multilabel.html
 * - http://scikit-learn.org/stable/modules/generated/sklearn.datasets.load_svmlight_file.html
 *
 * @author Adam Gibson     (original)
 * @author Josh Patterson
 * @author dave@skymind.io
 */
@Slf4j
public class SVMLightRecordReader extends LineRecordReader {
    /* Configuration options. */
    public static final String NAME_SPACE = SVMLightRecordReader.class.getName();
    public static final String NUM_FEATURES = NAME_SPACE + ".numfeatures";
    public static final String ZERO_BASED_INDEXING = NAME_SPACE + ".zeroBasedIndexing";
    public static final String MULTILABEL = NAME_SPACE + ".multilabel";
    public static final String NUM_LABELS = NAME_SPACE + ".numLabels";

    /* Constants. */
    public static final String COMMENT_CHAR = "#";
    public static final String ALLOWED_DELIMITERS = "[ \t]";
    public static final String PREFERRED_DELIMITER = " ";
    public static final String FEATURE_DELIMITER = ":";
    public static final String LABEL_DELIMITER = ",";
    public static final String QID_PREFIX = "qid";

    /* For convenience */
    public static final Writable ZERO = new DoubleWritable(0);
    public static final Writable ONE = new DoubleWritable(1);
    public static final Writable LABEL_ZERO = new IntWritable(0);
    public static final Writable LABEL_ONE = new IntWritable(1);

    protected int numFeatures = -1; // number of features
    protected boolean zeroBasedIndexing = true; /* whether to use zero-based indexing, true is safest
                                                 * but adds extraneous column if data is not zero indexed
                                                 */
    protected boolean appendLabel = true; // whether to append labels to output
    protected boolean multilabel = false; // whether targets are multilabel
    protected int numLabels = -1; // number of labels (required for multilabel targets)
    protected Writable recordLookahead = null;

    // for backwards compatibility
    public final static String NUM_ATTRIBUTES = NAME_SPACE + ".numattributes";

    public SVMLightRecordReader() {}

    /**
     * Must be called before attempting to read records.
     *
     * @param conf          DataVec configuration
     * @param split         FileSplit
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public void initialize(Configuration conf, InputSplit split) throws IOException, InterruptedException {
        super.initialize(conf, split);
        setConf(conf);
    }

    /**
     * Set configuration.
     *
     * @param conf          DataVec configuration
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public void setConf(Configuration conf) {
        super.setConf(conf);
        numFeatures = conf.getInt(NUM_FEATURES, -1);
        if (numFeatures < 0)
            numFeatures = conf.getInt(NUM_ATTRIBUTES, -1);
        if (numFeatures < 0)
            throw new UnsupportedOperationException("numFeatures must be set in configuration");
        appendLabel = conf.getBoolean(APPEND_LABEL, true);
        multilabel = conf.getBoolean(MULTILABEL, false);
        zeroBasedIndexing = conf.getBoolean(ZERO_BASED_INDEXING, true);
        numLabels = conf.getInt(NUM_LABELS, -1);
        if (multilabel && numLabels < 0)
            throw new UnsupportedOperationException("numLabels must be set in confirmation for multilabel problems");
    }

    /**
     * Helper function to help detect lines that are
     * commented out. May read ahead and cache a line.
     *
     * @return
     */
    protected Writable getNextRecord() {
        Writable w = null;
        if (recordLookahead != null) {
            w = recordLookahead;
            recordLookahead = null;
        }
        while (w == null && super.hasNext()) {
            w = super.next().iterator().next();
            if (!w.toString().startsWith(COMMENT_CHAR))
                break;
            w = null;
        }
        return w;
    }

    @Override
    public boolean hasNext() {
        recordLookahead = getNextRecord();
        return (recordLookahead != null);
    }

    /**
     * Return next record as list of Writables.
     *
     * @return
     */
    @Override
    public List<Writable> next() {
        Writable w = getNextRecord();
        if (w == null)
            throw new NoSuchElementException("No next element found!");
        String line = w.toString();
        List<Writable> record = new ArrayList<>();

        // Remove trailing comments
        String[] tokens = line.split(COMMENT_CHAR, 2)[0].trim().split(ALLOWED_DELIMITERS);

        // Iterate over feature tokens
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            // Split into feature index and value
            String[] featureTokens = token.split(FEATURE_DELIMITER);
            if (featureTokens[0].startsWith(QID_PREFIX)) {
                // Ignore QID entry for now
            } else {
                // Parse feature index -- enforce that it's a positive integer
                int index = -1;
                try {
                    index = Integer.parseInt(featureTokens[0]);
                    if (index < 0)
                        throw new NumberFormatException("");
                } catch (NumberFormatException e) {
                    String msg = String.format("Feature index must be positive integer (found %s)", featureTokens[i].toString());
                    throw new NumberFormatException(msg);
                }

                // If not using zero-based indexing, shift all indeces to left by one
                if (!zeroBasedIndexing) {
                    if (index == 0)
                        throw new IndexOutOfBoundsException("Found feature with index " + index + " but not using zero-based indexing");
                    index--;
                }

                // Check whether feature index exceeds number of features
                if (numFeatures >= 0 && index >= numFeatures)
                    throw new IndexOutOfBoundsException("Found " + (index+1) + " features in record, expected " + numFeatures);

                // Add remaining zero features
                while (record.size() < index)
                    record.add(ZERO);

                // Add feature
                record.add(new DoubleWritable(Double.parseDouble(featureTokens[1])));
            }
        }

        // Add remaining zero features
        while (record.size() < numFeatures)
            record.add(ZERO);

        // If labels should be appended
        if (appendLabel) {
            List<Writable> labels = new ArrayList<>();

            // Treat labels as indeces for multilabel binary classification
            if (multilabel) {
                String[] labelTokens = tokens[0].split(LABEL_DELIMITER);
                for (int i = 0; i < labelTokens.length; i++) {
                    // Parse label index -- enforce that it's a positive integer
                    int index = -1;
                    try {
                        index = Integer.parseInt(labelTokens[i]);
                        if (index < 0)
                            throw new NumberFormatException("");
                    } catch (NumberFormatException e) {
                        String msg = String.format("Multilabel index must be positive integer (found %s)", labelTokens[i].toString());
                        throw new NumberFormatException(msg);
                    }

                    // If not using zero-based indexing, shift all indeces to left by one
                    if (!zeroBasedIndexing) {
                        if (index == 0)
                            throw new IndexOutOfBoundsException("Found label with index " + index + " but not using zero-based indexing");
                        index--;
                    }

                    // Check whether label index exceeds number of labels
                    if (numLabels >= 0 && index >= numLabels)
                        throw new IndexOutOfBoundsException("Found " + (index+1) + " labels in record, expected " + numLabels);

                    // Add remaining zero features
                    while (labels.size() < index)
                        labels.add(LABEL_ZERO);

                    // Add label
                    labels.add(LABEL_ONE);
                }

                // Add remaining zero labels
                while (labels.size() < numLabels)
                    labels.add(LABEL_ZERO);
            } else {
                String[] labelTokens = tokens[0].split(LABEL_DELIMITER);
                if (numLabels < 0)
                    numLabels = labelTokens.length;
                if (labelTokens.length != numLabels)
                    throw new IndexOutOfBoundsException("Found " + labelTokens.length + " labels in record, expected " + numLabels);
                for (int i = 0; i < labelTokens.length; i++) {
                    try { // Encode label as integer, if possible
                        labels.add(new IntWritable(Integer.parseInt(labelTokens[i])));
                    } catch (NumberFormatException e) {
                        labels.add(new DoubleWritable(Double.parseDouble(labelTokens[i])));
                    }
                }
            }

            // Append labels to record
            record.addAll(labels);
        }

        return record;
    }

    /**
     * Return next Record.
     *
     * @return
     */
    @Override
    public Record nextRecord() {
        throw new UnsupportedOperationException("nextRecord has not been implemented for SVMLightRecordReader");
        /*
        List<Writable> next = next();
        URI uri = (locations == null || locations.length < 1 ? null : locations[splitIndex]);
        RecordMetaData meta = new RecordMetaDataLine(this.lineIndex - 1, uri, SVMLightRecordReader.class); //-1 as line number has been incremented already...
        return new org.datavec.api.records.impl.Record(next, meta);
        */
    }

    @Override
    public List<Writable> record(URI uri, DataInputStream dataInputStream) throws IOException {
        //Here: we are reading a single line from the DataInputStream. How to handle headers?
        throw new UnsupportedOperationException(
                "Reading SVMLightRecordReader data from DataInputStream not yet implemented");
    }

    @Override
    public void reset() {
        super.reset();
        recordLookahead = null;
    }

    @Override
    protected void onLocationOpen(URI location) {
        super.onLocationOpen(location);
        recordLookahead = null;
    }
}
