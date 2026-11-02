package com.homecare.identity;

import com.homecare.fixture.SchemaProbe;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatabaseFoundationIT {
    static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required integration environment: " + key);
        return value;
    }
    static String prefix() {
        String value = env("TEST_DATABASE_PREFIX");
        if (!value.matches("hc_test_[a-z0-9]+")) throw new IllegalStateException("Only disposable test databases permitted");
        return value;
    }
    static String url(String owner) {
        return "jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT") + "/" + prefix() + "_" + owner + "?connectTimeout=3";
    }
    static Connection connect(String owner, boolean migrate) throws SQLException {
        return DriverManager.getConnection(url(owner), prefix() + "_" + owner + (migrate ? "_migrator" : "_app"),
                env(owner.toUpperCase() + (migrate ? "_MIGRATOR_PASSWORD" : "_DB_PASSWORD")));
    }

    @Test void eachOwnerHasOnlyItsDatabaseAndNoDdlOrMigrationAccess() throws Exception {
        List<String> owners = List.of("identity", "household", "provider", "booking", "commerce", "review", "notification");
        for (String owner : owners) {
            try (var connection = connect(owner, false); var sql = connection.createStatement()) {
                try (var result = sql.executeQuery("SELECT current_database(), current_user")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(prefix() + "_" + owner);
                }
                assertThatThrownBy(() -> sql.execute("CREATE TABLE forbidden (id bigint)"))
                        .isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("42501");
                assertThatThrownBy(() -> sql.execute("CREATE TEMP TABLE forbidden (id bigint)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("SELECT * FROM migration.databasechangelog"))
                        .isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("42501");
            }
            for (String other : owners) if (!owner.equals(other)) {
                assertThatThrownBy(() -> DriverManager.getConnection(url(other), prefix() + "_" + owner + "_app",
                        env(owner.toUpperCase() + "_DB_PASSWORD")))
                        .isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("42501");
            }
            for (String maintenance : List.of("postgres", "template1")) {
                String maintenanceUrl = "jdbc:postgresql://127.0.0.1:" + env("POSTGRES_PORT") + "/" + maintenance + "?connectTimeout=3";
                assertThatThrownBy(() -> DriverManager.getConnection(maintenanceUrl, prefix() + "_" + owner + "_app",
                        env(owner.toUpperCase() + "_DB_PASSWORD")))
                        .isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("42501");
            }
        }
    }

    void validateSchema() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url("identity"))
                .applySetting("hibernate.connection.username", prefix() + "_identity_app")
                .applySetting("hibernate.connection.password", env("IDENTITY_DB_PASSWORD"))
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try (var factory = new MetadataSources(registry).addAnnotatedClass(SchemaProbe.class).buildMetadata().buildSessionFactory()) {
            assertThat(factory.isOpen()).isTrue();
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }

    @Test void migrationRerunChecksumAndHibernateDriftAreEnforced() throws Exception {
        var dir = Files.createTempDirectory("homecare-phase2-");
        var file = dir.resolve("fixture.sql");
        try (var input = getClass().getResourceAsStream("/fixture.sql")) { Files.copy(input, file); }
        try (var connection = connect("identity", true)) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setLiquibaseSchemaName("migration");
            try (var resources = new DirectoryResourceAccessor(dir);
                 var liquibase = new Liquibase("fixture.sql", resources, database)) {
                liquibase.update("");
                liquibase.update("");
                validateSchema();
                try (var runtime = connect("identity", false); var sql = runtime.createStatement()) {
                    sql.executeUpdate("INSERT INTO p2_schema_probe VALUES (1, 'synthetic')");
                    assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO p2_schema_probe VALUES (1, 'duplicate')"))
                            .isInstanceOf(SQLException.class);
                }
                Files.writeString(file, Files.readString(file).replace("varchar(40)", "varchar(41)"));
                try (var checksumConnection = connect("identity", true)) {
                    var checksumDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(checksumConnection));
                    checksumDatabase.setLiquibaseSchemaName("migration");
                    try (var changed = new Liquibase("fixture.sql", resources, checksumDatabase)) {
                        assertThatThrownBy(() -> changed.validate()).hasMessageContaining("Validation Failed");
                    }
                }
            }
        } finally {
            // Isolated test fixture only; never business schema or shared dev data.
            try (var cleanup = connect("identity", true); var sql = cleanup.createStatement()) {
                sql.execute("DROP TABLE IF EXISTS p2_schema_probe");
                sql.executeUpdate("DELETE FROM migration.databasechangelog WHERE id='1' AND author='phase2-test'");
            }
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
        assertThatThrownBy(this::validateSchema).hasMessageContaining("missing table");
    }
}
