package io.anserini.search;

import io.anserini.analysis.DefaultEnglishAnalyzer;
import io.anserini.index.IndexArgs;
import io.anserini.rerank.RerankerCascade;
import io.anserini.rerank.ScoredDocuments;
import io.anserini.rerank.lib.Rm3Reranker;
import io.anserini.search.similarity.TaggedSimilarity;
import io.anserini.search.topicreader.TopicReader;
import io.anserini.search.SearchArgs;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.tools.ant.taskdefs.Expand;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.kohsuke.args4j.ParserProperties;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExpandQueries implements Closeable {
    private final SearchArgs args;
    private final IndexReader reader;
    private static final Logger LOG = LogManager.getLogger(ExpandQueries.class);

    public ExpandQueries(SearchArgs args) throws IOException {
        this.args = args;
        Path indexPath = Paths.get(args.index);
        this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    public <K> void expandToFile(SortedMap<K, Map<String, String>> topics, Map<String, ScoredDocuments> trecRun) {
        HashMap<String, String> expandedTopics = new HashMap<String, String>();

        try {
            DefaultEnglishAnalyzer analyzer = DefaultEnglishAnalyzer.newNonStemmingInstance();
            Rm3Reranker reranker = new Rm3Reranker(
                    analyzer, IndexArgs.CONTENTS, Integer.valueOf(args.rm3_fbTerms[0]), Integer.valueOf(args.rm3_fbDocs[0]), Float.valueOf(args.rm3_originalQueryWeight[0]),
                    false
            );
            for (Map.Entry<K, Map<String, String>> entry : topics.entrySet()) {
                String qid = entry.getKey().toString();
                String queryText = entry.getValue().get(args.topicfield);
                String expandedTopic = reranker.getExpandedQuery(queryText, trecRun.get(qid), reader, args);
                expandedTopics.put(qid, expandedTopic);
            }

            PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(args.output), StandardCharsets.US_ASCII));
            for (Map.Entry<String, String> entry: expandedTopics.entrySet()) {
                String qid = entry.getKey();
                String expandedQueryText = entry.getValue();
                out.println(String.format(Locale.US, "%s\t%s", qid, expandedQueryText));
            }
            out.flush();
            out.close();
        }
        catch (IOException e) {
            LOG.info("error: " + e.toString());
        }
    }

    public <K> void runTopics() throws IOException {
        TopicReader<K> tr;
        SortedMap<K, Map<String, String>> topics = new TreeMap<>();
        for (String singleTopicsFile : args.topics) {
            Path topicsFilePath = Paths.get(singleTopicsFile);
            if (!Files.exists(topicsFilePath) || !Files.isRegularFile(topicsFilePath) || !Files.isReadable(topicsFilePath)) {
                throw new IllegalArgumentException("Topics file : " + topicsFilePath + " does not exist or is not a (readable) file.");
            }
            try {
                tr = (TopicReader<K>) Class.forName("io.anserini.search.topicreader." + args.topicReader + "TopicReader")
                        .getConstructor(Path.class).newInstance(topicsFilePath);
                topics.putAll(tr.read());
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalArgumentException("Unable to load topic reader: " + args.topicReader);
            }
        }

        final String runTag = args.runtag == null ? "Anserini" : args.runtag;
        LOG.info("runtag: " + runTag);

        Map<String, ScoredDocuments> trecRun = ScoredDocuments.fromRunFile(this.args.runFile, this.reader);
        expandToFile(topics, trecRun);
    }

    public static void main(String[] args) throws Exception {
        SearchArgs searchArgs = new SearchArgs();
        CmdLineParser parser = new CmdLineParser(searchArgs, ParserProperties.defaults().withUsageWidth(100));

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("Example: SearchCollection" + parser.printExample(OptionHandlerFilter.REQUIRED));
            return;
        }

        final long start = System.nanoTime();
        ExpandQueries expander;

        // We're at top-level already inside a main; makes no sense to propagate exceptions further, so reformat the
        // except messages and display on console.
        try {
            expander = new ExpandQueries(searchArgs);
        } catch (IllegalArgumentException e1) {
            System.err.println(e1.getMessage());
            return;
        }

        expander.runTopics();
        expander.close();
        final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        LOG.info("Total run time: " + DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"));
    }
}
