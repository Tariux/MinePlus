package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.persistence.PersistenceTx;
import com.mineplus.infrastructure.persistence.repository.MetaRepository;
import com.mineplus.infrastructure.persistence.repository.MultiBlockRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

public final class SqlitePersistenceTx implements PersistenceTx {

    private final Connection connection;
    private final Logger logger;
    private final MultiBlockRepository multiBlocks;
    private final MetaRepository meta;
    private boolean active;

    public SqlitePersistenceTx(Connection connection, Logger logger, MultiBlockRepository multiBlocks, MetaRepository meta) {
        this.connection = connection;
        this.logger = logger;
        this.multiBlocks = multiBlocks;
        this.meta = meta;
        this.active = true;
    }

    @Override
    public MultiBlockRepository multiBlocks() {
        return multiBlocks;
    }

    @Override
    public MetaRepository meta() {
        return meta;
    }

    @Override
    public void commit() {
        if (!active) {
            return;
        }
        try {
            connection.commit();
            active = false;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to commit sqlite transaction", exception);
        }
    }

    @Override
    public void rollback() {
        if (!active) {
            return;
        }
        try {
            connection.rollback();
            active = false;
        } catch (SQLException exception) {
            logger.warning("Failed to rollback sqlite transaction: " + exception.getMessage());
        }
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public void close() {
        if (active) {
            rollback();
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            logger.warning("Failed to close sqlite connection: " + exception.getMessage());
        }
    }
}
