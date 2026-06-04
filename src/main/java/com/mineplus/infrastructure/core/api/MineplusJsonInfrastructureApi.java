package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.core.InfrastructureEngine;

public final class MineplusJsonInfrastructureApi implements JsonInfrastructureApi {

    private final InfrastructureEngine engine;

    public MineplusJsonInfrastructureApi(InfrastructureEngine engine) {
        this.engine = engine;
    }

    @Override
    public void reloadAll() {
        engine.reloadAll();
    }

    @Override
    public void reloadModelDefinitions() {
        engine.reloadModelDefinitions();
    }

    @Override
    public void reloadMultiBlocks() {
        engine.reloadMultiBlocks();
    }

    @Override
    public void reloadRecipes() {
        engine.reloadRecipes();
    }
}
