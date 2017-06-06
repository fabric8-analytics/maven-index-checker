package com.redhat.maven.index.checker;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Bits;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexUtils;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.json.simple.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MavenIndexChecker {
    public static void main(String[] args)
            throws Exception {

        Options options = new Options();

        Option range = new Option("r", "range", true,
                "dash separated range in maven index. " +
                "Indexes are taken from end (the newest), i.e. eg. 0-1000 actually means from last-1000 to last.");
        range.setRequired(false);
        options.addOption(range);

        Option newOnlyOption = new Option("n", "new-only", false,
                "only entries added to index since last run. Is set to false if -r/--range is used.");
        newOnlyOption.setRequired(false);
        options.addOption(newOnlyOption);

        Option maxJarNumber = new Option("mj", "max-jars", true,
                "maximal number of jars to be printed. Too big number can cause memory problems.");
        maxJarNumber.setRequired(false);
        options.addOption(maxJarNumber);

        CommandLineParser parser = new BasicParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("maven-index-checker", options);

            System.exit(1);
            return;
        }

        boolean newOnly = false;
        if (cmd.hasOption("n")) {
            newOnly = true;
        }

        Range mavenIndexRange = null;
        if (cmd.hasOption("r")) {
            mavenIndexRange = parseRange(cmd.getOptionValue("r"));
            // Don't restrict to new only if -r/--range is used.
            newOnly = false;
        }

        int maxJarNumberValue = -1;
        if (cmd.hasOption("mj")) {
            maxJarNumberValue = Integer.parseInt(cmd.getOptionValue("mj"));
        }

        final MavenIndexChecker mavenIndexChecker = new MavenIndexChecker(newOnly, mavenIndexRange,
                maxJarNumberValue);
        mavenIndexChecker.perform();
    }

    private static Range parseRange(String mavenIndexRange) {
        String[] ranges = mavenIndexRange.split("-");
        if (ranges.length != 2) {
            throw new IllegalArgumentException("Given range is not separated correctly");
        }
        int[] intRanges = new int[2];
        try {
            for (int i = 0; i < 2; i++) {
                intRanges[i] = Integer.parseInt(ranges[i]);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Given range limit is not integer number");
        }

        // swap if there is bad order
        if (intRanges[0] > intRanges[1]) {
            int tmp = intRanges[1];
            intRanges[1] = intRanges[0];
            intRanges[0] = tmp;
        }
        return new Range(intRanges[0], intRanges[1]);
    }

    private final Logger logger;

    private final static String MAVEN_URL = "http://repo1.maven.org/maven2";

    private int maxJarNumber = 10000;

    private final PlexusContainer plexusContainer;

    private final Indexer indexer;

    private final IndexUpdater indexUpdater;

    private final Wagon httpWagon;

    private final boolean newOnly;

    private Range ranges = null;

    private static class Range {
        int start;
        int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private MavenIndexChecker(boolean newOnly, Range ranges, int maxJarNumber)
            throws PlexusContainerException, ComponentLookupException {
        final DefaultContainerConfiguration config = new DefaultContainerConfiguration();
        config.setClassPathScanning(PlexusConstants.SCANNING_INDEX);
        this.plexusContainer = new DefaultPlexusContainer(config);

        // lookup the indexer components from plexus
        this.indexer = plexusContainer.lookup(Indexer.class);
        this.indexUpdater = plexusContainer.lookup(IndexUpdater.class);
        // lookup wagon used to remotely fetch index
        this.httpWagon = plexusContainer.lookup(Wagon.class, "http");
        logger = LoggerFactory.getLogger(MavenIndexChecker.class);
        this.newOnly = newOnly;
        if (ranges != null)
            this.ranges = ranges;

        if(maxJarNumber != -1)
            this.maxJarNumber = maxJarNumber;
    }

    private void perform()
            throws IOException, ComponentLookupException, InvalidVersionSpecificationException {
        // Files where local cache is (if any) and Lucene Index should be located
        File centralLocalCache = new File("target/central-cache");
        File centralIndexDir = new File("target/central-index");

        // Creators we want to use (search for fields it defines)
        List<IndexCreator> indexers = new ArrayList<IndexCreator>();
        indexers.add(plexusContainer.lookup(IndexCreator.class, "min"));
        indexers.add(plexusContainer.lookup(IndexCreator.class, "jarContent"));
        indexers.add(plexusContainer.lookup(IndexCreator.class, "maven-plugin"));

        // Create context for central repository index
        IndexingContext centralContext =
                indexer.createIndexingContext("central-context", "central", centralLocalCache, centralIndexDir,
                        MAVEN_URL, null, true, true, indexers);

        // Update the index (incremental update will happen if this is not 1st run and files are not deleted)
        // This whole block below should not be executed on every app start, but rather controlled by some configuration
        // since this block will always emit at least one HTTP GET. Central indexes are updated once a week, but
        // other index sources might have different index publishing frequency.
        // Preferred frequency is once a week.
        Date previousCheck;
        boolean updated = true;
        {
            logger.info("Updating Index...");
            logger.info("This might take a while on first run, so please be patient!");
            // Create ResourceFetcher implementation to be used with IndexUpdateRequest
            // Here, we use Wagon based one as shorthand, but all we need is a ResourceFetcher implementation
            TransferListener listener = new AbstractTransferListener() {
                public void transferStarted(TransferEvent transferEvent) {
                    logger.info("  Downloading " + transferEvent.getResource().getName());
                }

                public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
                }

                public void transferCompleted(TransferEvent transferEvent) {
                    logger.info(" - Done");
                }
            };
            ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, listener, null, null);
            previousCheck = centralContext.getTimestamp();
            // if previousCheck date is null it indicates that no sync was done
            // set previousTime to linux epoch
            if (previousCheck == null) {
                previousCheck = new Date(0);
            }
            IndexUpdateRequest updateRequest = new IndexUpdateRequest(centralContext, resourceFetcher);
            IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
            if (updateResult.isFullUpdate()) {
                logger.info("Full update happened!");
            } else if (updateResult.getTimestamp().equals(previousCheck)) {
                logger.info("No update needed, index is up to date!");
                updated = false;
            } else {
                logger.info(
                        "Incremental update happened, change covered " + previousCheck + " - "
                                + updateResult.getTimestamp() + " period.");
            }
        }

        if (newOnly && !updated) {
            indexer.closeIndexingContext(centralContext, false);
            logger.info("No new entries since last run");
            return;
        }

        logger.info("Reading index");
        JSONArray results = new JSONArray();
        Set<OutputInfo> info = new HashSet<OutputInfo>();
        final IndexSearcher searcher = centralContext.acquireIndexSearcher();
        try {
            final IndexReader ir = searcher.getIndexReader();
            Bits liveDocs = MultiFields.getLiveDocs(ir);
            int max = ir.maxDoc() - 1;
            logger.info("entries: " + max);

            if (ranges == null) {
                ranges = new Range(0, max);
            } else if (ranges.end > max)
                ranges.end = max;

            // index is sorted from the oldest (0) to newest (max).
            // By default we are interested in the newest, so revert the range.
            Range reversed_range = new Range(max - ranges.end, max - ranges.start);
            logger.info("Checking: " + Integer.toString(reversed_range.start) + " - " +
                                       Integer.toString(reversed_range.end));

            logger.info("===========");
            for (int i = reversed_range.end; i > reversed_range.start; i--) {
                if (liveDocs != null && liveDocs.get(i)) {
                    final Document doc = ir.document(i);
                    final ArtifactInfo ai = IndexUtils.constructArtifactInfo(doc, centralContext);
                    if (ai != null) {
                        Date date = new Date(ai.getLastModified());
                        // we want to announce just jar's
                        if ("jar".equals(ai.getPackaging()) && (!newOnly || date.after(previousCheck))) {
                            info.add(new OutputInfo(ai.getArtifactId(), ai.getGroupId(), ai.getVersion()));
                        }

                        if (info.size() >= maxJarNumber) {
                            break;
                        }
                    }
                }
            }

            for (OutputInfo i : info) {
                results.add(i.toJSON());
            }
            System.out.println(results.toJSONString());
        } finally {
            centralContext.releaseIndexSearcher(searcher);
        }
        indexer.closeIndexingContext(centralContext, false);
    }
}