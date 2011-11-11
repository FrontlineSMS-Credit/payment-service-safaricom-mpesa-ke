package net.frontlinesms.payment.safaricom;

import java.io.IOException;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.data.events.EntitySavedNotification;
import net.frontlinesms.data.repository.ContactDao;
import net.frontlinesms.events.EventBus;
import net.frontlinesms.events.EventObserver;
import net.frontlinesms.events.FrontlineEventNotification;
import net.frontlinesms.payment.PaymentJob;
import net.frontlinesms.payment.PaymentJobProcessor;
import net.frontlinesms.payment.PaymentService;
import net.frontlinesms.payment.PaymentServiceException;

import org.apache.log4j.Logger;
import org.creditsms.plugins.paymentview.PaymentViewPluginController;
import org.creditsms.plugins.paymentview.analytics.TargetAnalytics;
import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.LogMessage;
import org.creditsms.plugins.paymentview.data.domain.PaymentServiceSettings;
import org.creditsms.plugins.paymentview.data.repository.AccountDao;
import org.creditsms.plugins.paymentview.data.repository.ClientDao;
import org.creditsms.plugins.paymentview.data.repository.IncomingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.LogMessageDao;
import org.creditsms.plugins.paymentview.data.repository.OutgoingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.TargetDao;
import org.creditsms.plugins.paymentview.userhomepropeties.payment.balance.Balance;
import org.creditsms.plugins.paymentview.userhomepropeties.payment.balance.BalanceProperties;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.ATHandler.SynchronizedWorkflow;

public abstract class AbstractPaymentService implements PaymentService, EventObserver{
	protected CService cService;
	protected String pin;
	protected Balance balance;
	protected EventBus eventBus;
	protected PaymentJobProcessor requestJobProcessor;
	protected PaymentJobProcessor responseJobProcessor;
	protected AccountDao accountDao;
	protected ClientDao clientDao;
	protected TargetDao targetDao;
	protected IncomingPaymentDao incomingPaymentDao;
	protected OutgoingPaymentDao outgoingPaymentDao;
	protected LogMessageDao logMessageDao;
	protected ContactDao contactDao;
	private PaymentServiceSettings settings;
	protected BalanceDispatcher balanceDispatcher;
	
	protected Logger pvLog = Logger.getLogger(this.getClass());
	protected TargetAnalytics targetAnalytics;

	public AbstractPaymentService() {
		super();
	}
	
	public enum Status {
		SENDING("Sending payment(s) ..."),
		COMPLETE("Payment Process completed."),
		RECEIVING("Processing Incoming Payment ..."),
		PROCESSED("New Incoming Payment has been received."),
		CHECK_BALANCE("Checking Balance ..."),
		CHECK_COMPLETE("Check Balance Complete."),
		CONFIGURE_STARTED("Configuring Modem ..."),
		CONFIGURE_COMPLETE("Modem Configuration Complete."),
		PAYMENTSERVICE_OFF("Payment Service Not Setup."),
		PAYMENTSERVICE_ON("Payment Service is Set Up."),
		
		ERROR("Error occurred.");
		
		private final String statusMessage;

		Status(String statusMessage){
			this.statusMessage = statusMessage;
		}
		
		@Override
		public String toString() {
			return statusMessage;
		}
	}

	public void configureModem() throws PaymentServiceException {
		final CService cService = this.cService;
		queueRequestJob(new PaymentJob() {
			public void run() {
				try{
					cService.doSynchronized(new SynchronizedWorkflow<Object>() {
						public Object run() throws SMSLibDeviceException, IOException {
							updateStatus(Status.CONFIGURE_STARTED);
							cService.getAtHandler().configureModem();
							updateStatus(Status.CONFIGURE_COMPLETE);
							return null;
						}
					});
				} catch (final SMSLibDeviceException ex) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"SMSLibDeviceException in configureModem()",
							ex.getMessage()));
					updateStatus(Status.ERROR);
				} catch (final IOException e) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"IOException in configureModem()", e.getMessage()));
					updateStatus(Status.ERROR);
				} catch (RuntimeException e) {
					logMessageDao.saveLogMessage(LogMessage.error(
							"configureModem failed for some reason.", e.getMessage()));
					updateStatus(Status.ERROR);
				}
			}
		});
	}

	@SuppressWarnings("rawtypes")
	public void notify(final FrontlineEventNotification notification) {
		if (!(notification instanceof EntitySavedNotification)) {
			return;
		}
		
		//And is of a saved message
		final Object entity = ((EntitySavedNotification) notification).getDatabaseEntity();
		if (!(entity instanceof FrontlineMessage)) {
			return;
		}
		final FrontlineMessage message = (FrontlineMessage) entity;
		processMessage(message);
	}
	
	protected abstract void processMessage(final FrontlineMessage message);

	protected String getFirstMatch(final String string, final String regexMatcher) {
		final Matcher matcher = Pattern.compile(regexMatcher).matcher(string);
		matcher.find();
		return matcher.group();
	}

	protected String getFirstMatch(final FrontlineMessage message, final String regexMatcher) {
		return getFirstMatch(message.getTextContent(), regexMatcher);
	}

	@Override
	public boolean equals(final Object other) {
		if (!(other instanceof PaymentService)){
			return false;
		}
		
		if (!(other instanceof MpesaPaymentService)){
			return false;
		}
		
		return super.equals(other);
	}

	protected void initIfRequired() throws SMSLibDeviceException,
			IOException {
				// For now, we assume that init is always required.  If there is a clean way
				// of identifying when it is and is not, we should perhaps implement this.
				this.cService.getAtHandler().stkInit();
			}

	public void stop() {
		eventBus.unregisterObserver(this);
		requestJobProcessor.stop();
		responseJobProcessor.stop();
	}

	public String getPin() {
		return pin;
	}

	public void registerToEventBus(final EventBus eventBus) {
		if (eventBus != null) {
			this.eventBus = eventBus;
			this.eventBus.registerObserver(this);
		}
	}

	/** @return the settings attached to this instance. */
	public PaymentServiceSettings getSettings() {
		return settings;
	}

	/**
	 * Initialise the service using the supplied properties.
	 */
	public void setSettings(PaymentServiceSettings settings) {
		this.settings = settings;
	}

	public void setPin(final String pin) {
		this.pin = pin;
	}

	public void setCService(final CService cService) {
		this.cService = cService;
	}

	public void setBalance(Balance balance) {
		this.balance = balance;
	}
	
	public void setBalanceDispatcher(BalanceDispatcher balanceDispatcher) {
		this.balanceDispatcher = balanceDispatcher;
	}
	
	public Balance getBalance() {
		return balance;
	}

	public void initDaosAndServices(final PaymentViewPluginController pluginController) {
		this.accountDao = pluginController.getAccountDao();
		this.clientDao = pluginController.getClientDao();
		this.outgoingPaymentDao = pluginController.getOutgoingPaymentDao();
		this.targetDao = pluginController.getTargetDao();
		this.incomingPaymentDao = pluginController.getIncomingPaymentDao();
		this.targetAnalytics = pluginController.getTargetAnalytics();
		this.logMessageDao = pluginController.getLogMessageDao();
		if(this.balance == null) this.balance = BalanceProperties.getInstance().getBalance(this);
		if(this.balanceDispatcher == null) this.balanceDispatcher = BalanceDispatcher.getInstance();
		this.contactDao = pluginController.getUiGeneratorController().getFrontlineController().getContactDao();
		
		this.registerToEventBus(
			pluginController.getUiGeneratorController().getFrontlineController().getEventBus()
		);
		
		this.balance.setEventBus(this.eventBus);
		this.pvLog = pluginController.getLogger(this.getClass());
		
		this.requestJobProcessor = new PaymentJobProcessor(this);
		this.requestJobProcessor.start();
		
		this.responseJobProcessor = new PaymentJobProcessor(this);
		this.responseJobProcessor.start();
	}
	
	void queueRequestJob(PaymentJob job) {
		requestJobProcessor.queue(job);
	}
	
	void queueResponseJob(PaymentJob job) {
		responseJobProcessor.queue(job);
	}

	public CService getCService() {
		return cService;
	}
	
	public void updateStatus(Status sending) {
		if (eventBus != null){
			eventBus.notifyObservers(new PaymentStatusEventNotification(sending));
		}
	}
	
	public static class PaymentStatusEventNotification implements FrontlineEventNotification {
		private final Status status;
		public PaymentStatusEventNotification(Status status) {
			this.status = status;
		}

		public Status getPaymentStatus() {
			return status;
		}
	}
	
	abstract Date getTimePaid(FrontlineMessage message);
	abstract boolean isMessageTextValid(String message);
	abstract Account getAccount(FrontlineMessage message);
	abstract String getPaymentBy(FrontlineMessage message);
	protected abstract boolean isValidBalanceMessage(FrontlineMessage message);
	
	public class BalanceFraudNotification implements FrontlineEventNotification{
		private final String message;
		public BalanceFraudNotification(String message){this.message=message;}
		public String getMessage(){return message;}
	}
}