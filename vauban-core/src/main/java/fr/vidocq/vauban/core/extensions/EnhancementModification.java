package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.indexer.model.DotName;

import java.util.List;


public record EnhancementModification(
        DotName beanClass,
        List<VaubanClassConfig> classConfigs
) {}
