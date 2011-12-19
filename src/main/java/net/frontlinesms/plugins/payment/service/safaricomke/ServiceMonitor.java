package net.frontlinesms.plugins.payment.service.safaricomke;

import org.creditsms.plugins.paymentview.data.repository.PaymentServiceSettingsDao;
import org.springframework.context.ApplicationContext;

import net.frontlinesms.FrontlineSMS;
import net.frontlinesms.data.domain.PersistableSettings;
import net.frontlinesms.events.EventBus;
import net.frontlinesms.events.FrontlineEventNotification;
import net.frontlinesms.messaging.sms.events.SmsModemStatusNotification;
import net.frontlinesms.messaging.sms.modem.SmsModemStatus;
import net.frontlinesms.plugins.payment.monitor.PaymentServiceMonitor;
import net.frontlinesms.plugins.payment.service.PaymentServiceStartRequest;

public class ServiceMonitor implements PaymentServiceMonitor {
	private EventBus eventBus;
	private PaymentServiceSettingsDao settingsDao;
	
	public void init(FrontlineSMS frontlineController, ApplicationContext applicationContext) {
		this.eventBus = frontlineController.getEventBus();
		this.settingsDao = (PaymentServiceSettingsDao) applicationContext.getBean("paymentServiceSettingsDao");
	}
	
	public void notify(FrontlineEventNotification notification) {
		if(notification instanceof SmsModemStatusNotification) {
			processModemStatusNotification((SmsModemStatusNotification) notification);
		}
	}

	private void processModemStatusNotification(SmsModemStatusNotification notification) {
		if(notification.getStatus() == SmsModemStatus.CONNECTED) {
			String serial = notification.getService().getSerial();
			PersistableSettings settings = settingsDao.getByProperty(AbstractPaymentService.PROPERTY_MODEM_SERIAL, serial);
			if(settings != null) {
				eventBus.notifyObservers(new PaymentServiceStartRequest(settings));
			}
		}
	}
}
