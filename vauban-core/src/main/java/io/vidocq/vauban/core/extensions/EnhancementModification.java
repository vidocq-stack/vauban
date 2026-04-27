package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.indexer.model.DotName;

import java.util.List;


public record EnhancementModification(
        DotName beanClass,
        List<VaubanClassConfig> classConfigs
) {}
