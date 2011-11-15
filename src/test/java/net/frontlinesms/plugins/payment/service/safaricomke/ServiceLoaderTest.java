package net.frontlinesms.plugins.payment.service.safaricomke;

import java.util.List;

import net.frontlinesms.junit.BaseTestCase;
import net.frontlinesms.plugins.payment.service.PaymentService;
import net.frontlinesms.plugins.payment.service.PaymentServiceImplementationLoader;

public class ServiceLoaderTest extends BaseTestCase {
	public void testPersonalServiceLoading() {
		List<Class<? extends PaymentService>> services = new PaymentServiceImplementationLoader().getAll();
		assertTrue(services.contains(MpesaPersonalService.class));
	}
	
	public void testPayBillServiceLoading() {
		List<Class<? extends PaymentService>> services = new PaymentServiceImplementationLoader().getAll();
		assertTrue(services.contains(MpesaPayBillService.class));
	}
}
