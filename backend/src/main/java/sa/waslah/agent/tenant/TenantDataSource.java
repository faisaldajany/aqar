package sa.waslah.agent.tenant;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Sets app.office_id on each connection handed out, and clears it when the connection goes back
 * to the pool, so a pooled connection never carries one office's id into another request.
 * With no office in context the setting is empty and RLS returns no tenant rows.
 */
public class TenantDataSource extends DelegatingDataSource {

    private static final String SET = "select set_config('app.office_id', ?, false)";

    public TenantDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    private Connection bind(Connection target) throws SQLException {
        String office = TenantContext.current().map(UUID::toString).orElse("");
        apply(target, office);
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && !target.isClosed()) {
                        try {
                            apply(target, "");
                        } catch (SQLException ignored) {
                            // The pool validates connections; a broken one is discarded there.
                        }
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void apply(Connection connection, String office) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SET)) {
            ps.setString(1, office);
            ps.execute();
        }
    }
}
