package com.kk.pde.ds.imp;

import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.api.IGreet;

/**
 * Health check for the IGreet service.
 *
 * This health check verifies that the IGreet service is available
 * and can be invoked successfully.
 */
@Component(
    service = HealthCheck.class,
    property = {
        HealthCheck.NAME + "=Greet Service Health Check",
        HealthCheck.TAGS + "=application,greet",
        HealthCheck.MBEAN_NAME + "=greetServiceHealthCheck"
    }
)
public class GreetHealthCheck implements HealthCheck {

    private static final Logger log = LoggerFactory.getLogger(GreetHealthCheck.class);

    @Reference
    private IGreet greetService;

    @Activate
    public void activate() {
        log.info("GreetHealthCheck activated");
    }

    @Override
    public Result execute() {
        FormattingResultLog resultLog = new FormattingResultLog();

        log.debug("Executing Greet Service Health Check");

        try {
            // greetService is a mandatory (1..1) static reference — SCR guarantees it is
            // non-null while this component is active, so no null check is needed here.
            greetService.greet();

            resultLog.info("IGreet service is available and operational");
            resultLog.info("Service implementation: {}", greetService.getClass().getName());

        } catch (Exception e) {
            log.error("Greet Service Health Check failed", e);
            resultLog.critical("IGreet service invocation failed: {}", e.getMessage());
        }

        return new Result(resultLog);
    }
}
