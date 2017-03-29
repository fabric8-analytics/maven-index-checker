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
import org.json.simple.JSONObject;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MavenIndexChecker {
    public static void main(String[] args)
            throws Exception {

        final MavenIndexChecker mavenIndexChecker = new MavenIndexChecker();
        mavenIndexChecker.perform();
    }

    private static String toJSON(String artifactId, String groupId, String version) {
        JSONObject obj = new JSONObject();
            obj.put("artifactId", artifactId);
            obj.put("groupId", groupId);
            obj.put("version", version);
            return obj.toJSONString();
    }

    private final Logger logger;

    private final PlexusContainer plexusContainer;

    private final Indexer indexer;

    private final IndexUpdater indexUpdater;

    private final Wagon httpWagon;

    private MavenIndexChecker()
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
        // set number of newest packages to synchronize
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
                        "http://repo1.maven.org/maven2", null, true, true, indexers);

        // Update the index (incremental update will happen if this is not 1st run and files are not deleted)
        // This whole block below should not be executed on every app start, but rather controlled by some configuration
        // since this block will always emit at least one HTTP GET. Central indexes are updated once a week, but
        // other index sources might have different index publishing frequency.
        // Preferred frequency is once a week.
        Date previousCheck;
        boolean syncRequired = true;
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
                syncRequired = false;
            } else {
                logger.info(
                        "Incremental update happened, change covered " + previousCheck + " - "
                                + updateResult.getTimestamp() + " period.");
            }

        }

        logger.info("Reading index");
        logger.info("===========");

        JSONArray jsArray = new JSONArray();
        if (syncRequired) {
            final IndexSearcher searcher = centralContext.acquireIndexSearcher();
            try {
                final IndexReader ir = searcher.getIndexReader();
                Bits liveDocs = MultiFields.getLiveDocs(ir);
                int max = ir.maxDoc() - 1;
                for (int i = 0; i < 50; i++, max--) {
                    if (liveDocs == null || liveDocs.get(max)) {
                        final Document doc = ir.document(max);
                        final ArtifactInfo ai = IndexUtils.constructArtifactInfo(doc, centralContext);

                        if (ai != null) {
                            Date date = new Date(ai.getLastModified());
                            // we want to announce just jar's
                            if ("jar".equals(ai.getPackaging()) && date.after(previousCheck))
                                jsArray.add(toJSON(ai.getArtifactId(), ai.getGroupId(), ai.getVersion()));
                        }
                    }
                }
                System.out.println(jsArray.toJSONString());

            } finally {
                centralContext.releaseIndexSearcher(searcher);
            }
        }

        indexer.closeIndexingContext(centralContext, false);
    }
}
