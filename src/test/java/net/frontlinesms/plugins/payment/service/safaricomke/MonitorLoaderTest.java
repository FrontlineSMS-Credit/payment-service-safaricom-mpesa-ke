package net.frontlinesms.plugins.payment.service.safaricomke;

import java.util.List;

import net.frontlinesms.junit.BaseTestCase;
import net.frontlinesms.plugins.payment.monitor.PaymentServiceMonitor;
import net.frontlinesms.plugins.payment.monitor.PaymentServiceMonitorImplementationLoader;

public class MonitorLoaderTest extends BaseTestCase {
	public void testMonitorLoading() {
		List<Class<? extends PaymentServiceMonitor>> services = new PaymentServiceMonitorImplementationLoader().getAll();
		assertTrue(services.contains(ServiceMonitor.class));
	}
}
