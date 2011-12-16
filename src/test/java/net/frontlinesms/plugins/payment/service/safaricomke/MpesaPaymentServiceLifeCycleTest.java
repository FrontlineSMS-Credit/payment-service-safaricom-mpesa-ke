package net.frontlinesms.plugins.payment.service.safaricomke;

import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;

import net.frontlinesms.FrontlineSMS;
import net.frontlinesms.data.domain.PersistableSettings;
import net.frontlinesms.events.EventBus;
import net.frontlinesms.events.FrontlineEventNotification;
import net.frontlinesms.junit.BaseTestCase;
import net.frontlinesms.messaging.sms.events.SmsModemStatusNotification;
import net.frontlinesms.messaging.sms.modem.SmsModemStatus;
//import net.frontlinesms.test.smslib.SmsLibTestUtils;
import net.frontlinesms.ui.UiGeneratorController;

import org.apache.log4j.Logger;
import org.creditsms.plugins.paymentview.PaymentViewPluginController;
import org.creditsms.plugins.paymentview.analytics.TargetAnalytics;
import org.creditsms.plugins.paymentview.data.repository.LogMessageDao;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.CATHandler_Wavecom_Stk;

/** Unit tests for {@link MpesaPaymentService} */
public abstract class MpesaPaymentServiceLifeCycleTest<E extends MpesaPaymentService>
		extends BaseTestCase {
	private static final String TEST_PIN = "1234";

	private static enum SMS_MODEM_STATUS_NOTIFICATION {
		CONNECTED;

		@Override
		public String toString() {
			return name().toLowerCase().replace('_', ' ');
		}
	}

	protected LogMessageDao logMessageDao;
	private UiGeneratorController ui;
	protected E mpesaPaymentService;
	protected Logger logger;
	private PaymentViewPluginController pluginController;
	private CService cService;
	private CATHandler_Wavecom_Stk aTHandler;

	@SuppressWarnings("unchecked")
	private void setUpDaos() throws Exception {
		super.setUp();

		pluginController = mock(PaymentViewPluginController.class);
		ui = mock(UiGeneratorController.class);

		FrontlineSMS fsms = mock(FrontlineSMS.class);
		EventBus eventBus = mock(EventBus.class);
		mpesaPaymentService.registerToEventBus(eventBus);
		when(fsms.getEventBus()).thenReturn(eventBus);
		when(ui.getFrontlineController()).thenReturn(fsms);

		// Set Up Rules
		when(pluginController.getLogMessageDao()).thenReturn(logMessageDao);
		when(pluginController.getUiGeneratorController()).thenReturn(ui);
		mpesaPaymentService.init(pluginController);
	}

	public void startPaymentServicetest() throws SMSLibDeviceException,
			IOException {

		SmsModemStatusNotification notification = new SmsModemStatusNotification(null, null);
		if (when(((SmsModemStatusNotification) notification).getStatus())
				.thenReturn(SmsModemStatus.CONNECTED).toString() == SmsModemStatus.CONNECTED.toString()) {
			
/*			
			Collection<PersistableSettings> persistableSettingsList = paymentServiceSettingsDao.getPaymentServiceAccounts();
			if (!persistableSettingsList.isEmpty()){
				for (PersistableSettings psSettings : persistableSettingsList){
					if (psSettings.getPsSmsModemSerial().equals(connectedModem.getSerial()+"@"+connectedModem.getImsiNumber())) {
						// We've just connected the configured device, so start up the payment service...
						//...if it's not already running!
						MpesaPaymentService mpesaPaymentService = (MpesaPaymentService) psSettings.initPaymentService();
						if(mpesaPaymentService != null) {
							if(psSettings.getPsPin() != null) {
								
							}							
						}						
					}				
				}				
			}
*/
			this.mpesaPaymentService = (E) createNewTestClass();
			this.cService = SmsLibTestUtils.mockCService();
			this.aTHandler = mock(CATHandler_Wavecom_Stk.class);
			when(cService.getAtHandler()).thenReturn(aTHandler);
			mpesaPaymentService.setPin(TEST_PIN);
			mpesaPaymentService.setCService(cService);

			mpesaPaymentService.setSettings(mockSettings(
					AbstractPaymentService.PROPERTY_PIN, TEST_PIN,
					AbstractPaymentService.PROPERTY_MODEM_SERIAL, "093SH5S655",
					AbstractPaymentService.PROPERTY_BALANCE_AMOUNT, new BigDecimal(
							"200"),
					AbstractPaymentService.PROPERTY_BALANCE_UPDATE_METHOD,
					"balance enquiry",
					AbstractPaymentService.PROPERTY_BALANCE_CONFIRMATION_CODE,
					"7HHSK457S", AbstractPaymentService.PROPERTY_BALANCE_DATE_TIME,
					System.currentTimeMillis()));
			
		}
	}

	private PersistableSettings mockSettings(Object... settingsAndValues) {
		assert ((settingsAndValues.length & 1) == 0) : "Should be an equal number of setting keys and value";
		PersistableSettings s = new PersistableSettings(mpesaPaymentService);
		for (int i = 0; i < settingsAndValues.length; i += 2) {
			String key = (String) settingsAndValues[i];
			Object value = settingsAndValues[i + 1];
			s.set(key, value);
		}

		return s;
	}

	protected abstract E createNewTestClass();
}
