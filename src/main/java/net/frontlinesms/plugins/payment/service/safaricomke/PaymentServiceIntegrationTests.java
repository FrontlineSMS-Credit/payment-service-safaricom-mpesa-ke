//package net.frontlinesms.plugins.payment.service.safaricomke;
//
//import org.creditsms.plugins.paymentview.data.repository.PaymentServiceSettingsDao;
//
//import net.frontlinesms.junit.BaseTestCase;
//
//public class PaymentServiceIntegrationTests extends BaseTestCase {
///*
// * TITLE: when a new modem is connected, FrontlineSMS SHOULD prompt
//the user to create a payment service connected to it
//GIVEN there are no configured payment services
//WHEN modem is connected
//THEN the user is prompted to create a new payment service
//connected to the new modem
// */
//	public void testModemConnectionShouldTriggerNewServiceConvfigurationPrompt() {
//		// GIVEN there are no configured payment services
//		PaymentServiceSettingsDao dao = mock(PaymentServiceSettingsDao.class);
//		
//		// WHEN modem is connected
//		
//		eventBus.notifyObservers(new ModemConnectedNotification());
//		
//		// THEN the user is prompted to create a new payment service
//		connected to the new modem
//	}
//}
