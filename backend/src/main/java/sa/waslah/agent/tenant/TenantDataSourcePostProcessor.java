package sa.waslah.agent.tenant;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/** Wraps the application DataSource (not Flyway's) so every connection carries the tenant. */
@Component
public class TenantDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if ("dataSource".equals(beanName) && bean instanceof DataSource ds && !(bean instanceof TenantDataSource)) {
            return new TenantDataSource(ds);
        }
        return bean;
    }
}
