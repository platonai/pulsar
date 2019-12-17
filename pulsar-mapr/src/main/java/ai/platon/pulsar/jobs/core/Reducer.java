/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package ai.platon.pulsar.jobs.core;

import ai.platon.pulsar.common.DateTimeUtil;
import ai.platon.pulsar.common.MetricsCounters;
import ai.platon.pulsar.common.MetricsReporter;
import ai.platon.pulsar.common.config.Configurable;
import ai.platon.pulsar.common.config.ImmutableConfig;
import ai.platon.pulsar.common.config.Params;
import org.apache.hadoop.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;

import static ai.platon.pulsar.common.config.CapabilityTypes.BATCH_ID;
import static ai.platon.pulsar.common.config.CapabilityTypes.STORAGE_CRAWL_ID;

public class Reducer<K1, V1, K2, V2> extends org.apache.hadoop.mapreduce.Reducer<K1, V1, K2, V2> implements Configurable {

    protected static final Logger log = LoggerFactory.getLogger(Reducer.class.getName());

    protected Context context;

    /**
     * The job conf is passed from a MapReduce Job
     * */
    protected ImmutableConfig jobConf;
    protected MetricsCounters metricsCounters;
    protected MetricsReporter metricsReporter;

    protected boolean completed = false;

    protected Instant startTime = Instant.now();

    protected void beforeSetup(Context context) throws IOException, InterruptedException {
        this.context = context;
        this.jobConf = new ImmutableConfig(context.getConfiguration());

        this.metricsCounters = new MetricsCounters();
        this.metricsReporter = new MetricsReporter(context.getJobName(), metricsCounters, jobConf, context);

        String crawlId = jobConf.get(STORAGE_CRAWL_ID);
        String batchId = jobConf.get(BATCH_ID);

        log.info(Params.formatAsLine(
                "---- reducer setup ", " ----",
                "className", this.getClass().getSimpleName(),
                "startTime", DateTimeUtil.format(startTime),
                "crawlId", crawlId,
                "batchId", batchId,
                "reducerTasks", context.getNumReduceTasks(),
                "hostname", metricsCounters.getHostname()
        ));
    }

    @Override
    public void run(Context context) {
        try {
            beforeSetup(context);
            setup(context);

            doRun(context);

            cleanup(context);

            cleanupContext(context);
        } catch (Throwable e) {
            log.error(StringUtils.stringifyException(e));
        } finally {
            afterCleanup(context);
        }
    }

    protected void doRun(Context context) throws IOException, InterruptedException {
        while (!completed && context.nextKey()) {
            reduce(context.getCurrentKey(), context.getValues(), context);
        }
    }

    protected void cleanupContext(Context context) throws Exception {
    }

    protected void afterCleanup(Context context) {
        context.setStatus(metricsCounters.getStatus(true));
        metricsReporter.stopReporter();

        log.info(Params.formatAsLine(
                "---- reducer cleanup ", " ----",
                "className", this.getClass().getSimpleName(),
                "startTime", DateTimeUtil.format(startTime),
                "finishTime", DateTimeUtil.now(),
                "timeElapsed", DateTimeUtil.elapsedTime(startTime)
        ));
    }

    protected boolean completed() {
        return completed;
    }

    protected void abort() {
        completed = true;
    }

    protected void abort(String error) {
        log.error(error);
        completed = true;
    }

    protected void stop() {
        completed = true;
    }

    protected void stop(String info) {
        log.info(info);
        completed = true;
    }

    protected MetricsCounters getMetricsCounters() {
        return metricsCounters;
    }

    protected MetricsReporter getMetricsReporter() {
        return metricsReporter;
    }

    @Override
    public ImmutableConfig getConf() {
        return jobConf;
    }

    @Override
    public void setConf(ImmutableConfig jobConf) {
        this.jobConf = jobConf;
    }
}
