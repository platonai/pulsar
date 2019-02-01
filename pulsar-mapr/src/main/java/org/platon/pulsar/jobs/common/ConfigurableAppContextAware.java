package org.platon.pulsar.jobs.common;

import ai.platon.pulsar.common.config.Configurable;
import ai.platon.pulsar.common.config.ImmutableConfig;
import ai.platon.pulsar.common.config.MutableConfig;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import static ai.platon.pulsar.common.config.CapabilityTypes.APPLICATION_CONTEXT_CONFIG_LOCATION;
import static ai.platon.pulsar.common.config.PulsarConstants.JOB_CONTEXT_CONFIG_LOCATION;

/**
 * Created by vincent on 17-4-17.
 * Copyright @ 2013-2017 Platon AI. All rights reserved
 */
public interface ConfigurableAppContextAware extends Configurable {

    ConfigurableApplicationContext getApplicationContext();

    void setApplicationContext(ConfigurableApplicationContext applicationContext);

    /**
     * Notice : generally, Job Creation, Mapper and Reducer runs on different hosts,
     * so application context is loaded from file system in each phrase.
     * Because the configuration can be modified in previous phrase, we must init all configurable beans
     * after the configuration is updated
     */
    default void initApplicationContext(Configuration contextConfiguration) {
        String contextConfigLocation = contextConfiguration
                .get(APPLICATION_CONTEXT_CONFIG_LOCATION, JOB_CONTEXT_CONFIG_LOCATION);

        Logger LOG = LoggerFactory.getLogger(ConfigurableAppContextAware.class);
        LOG.info("Spring Context config location : " + contextConfigLocation);

        ConfigurableApplicationContext applicationContext = new ClassPathXmlApplicationContext(contextConfigLocation);
        applicationContext.registerShutdownHook();

        // Notice: most other beans rely on conf bean, conf bean must be updated before all other bean's initialization
        MutableConfig conf = new MutableConfig(applicationContext.getBean(ImmutableConfig.class));
        conf.reset(contextConfiguration);

        if (conf.size() != contextConfiguration.size()) {
            LOG.error("Config size does not match!!!");
        }

        setConf(conf);
        setApplicationContext(applicationContext);
    }
}