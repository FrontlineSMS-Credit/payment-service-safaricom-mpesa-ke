package net.frontlinesms.plugins.payment.service.safaricomke;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.frontlinesms.FrontlineUtils;
import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.data.domain.PersistableSettings;
import net.frontlinesms.data.events.EntitySavedNotification;
import net.frontlinesms.data.repository.ContactDao;
import net.frontlinesms.events.EventBus;
import net.frontlinesms.events.EventObserver;
import net.frontlinesms.events.FrontlineEventNotification;
import net.frontlinesms.plugins.payment.event.PaymentStatusEventNotification;
import net.frontlinesms.plugins.payment.service.PaymentJob;
import net.frontlinesms.plugins.payment.service.PaymentJobProcessor;
import net.frontlinesms.plugins.payment.service.PaymentService;
import net.frontlinesms.plugins.payment.service.PaymentServiceException;
import net.frontlinesms.plugins.payment.service.PaymentStatus;
import net.frontlinesms.serviceconfig.ConfigurableService;
import net.frontlinesms.serviceconfig.StructuredProperties;

import org.apache.log4j.Logger;
import org.creditsms.plugins.paymentview.PaymentViewPluginController;
import org.creditsms.plugins.paymentview.analytics.TargetAnalytics;
import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.repository.AccountDao;
import org.creditsms.plugins.paymentview.data.repository.ClientDao;
import org.creditsms.plugins.paymentview.data.repository.IncomingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.LogMessageDao;
import org.creditsms.plugins.paymentview.data.repository.OutgoingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.TargetDao;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.ATHandler.SynchronizedWorkflow;

public abstract class AbstractPaymentService implements PaymentService, EventObserver {
//> PERSISTENT PROPERTIES
	/** Prefix attached to every property name. */
	private static final String PROPERTY_PREFIX = "plugins.payment.mpesa.";

	protected static final String PROPERTY_PIN = PROPERTY_PREFIX + "pin";
	protected static final String PROPERTY_BALANCE_CONFIRMATION_CODE = PROPERTY_PREFIX + "balance.confirmation";
	protected static final String PROPERTY_BALANCE_AMOUNT = PROPERTY_PREFIX + "balance.amount";
	protected static final String PROPERTY_BALANCE_DATE_TIME = PROPERTY_PREFIX + "balance.timestamp";
	protected static final String PROPERTY_BALANCE_UPDATE_METHOD = PROPERTY_PREFIX + "balance.update.method";
	protected static final String PROPERTY_MODEM_SERIAL = PROPERTY_PREFIX + "modem.serial";
	
	public String getBalanceConfirmationCode() {
		return getProperty(PROPERTY_BALANCE_CONFIRMATION_CODE, String.class);
	}
	public void setBalanceAmount(BigDecimal balanceAmount) {
		setProperty(PROPERTY_BALANCE_AMOUNT, balanceAmount);
	}
	public BigDecimal getBalanceAmount() {
		return getProperty(PROPERTY_BALANCE_AMOUNT, BigDecimal.class);
	}
	public void setBalanceConfirmationCode(String balanceConfirmationCode) {
		setProperty(PROPERTY_BALANCE_CONFIRMATION_CODE, balanceConfirmationCode);
	}
	public Date getBalanceDateTime() {
		return new Date(getProperty(PROPERTY_BALANCE_DATE_TIME, Long.class));
	}
	public void setBalanceDateTime(Date balanceDateTime) {
		setProperty(PROPERTY_BALANCE_DATE_TIME, balanceDateTime.getTime());
	}
	public String getBalanceUpdateMethod() {
		return getProperty(PROPERTY_BALANCE_UPDATE_METHOD, String.class);
	}
	public void setBalanceUpdateMethod(String balanceUpdateMethod) {
		setProperty(PROPERTY_BALANCE_UPDATE_METHOD, balanceUpdateMethod);
	}
	public String getPsSmsModemSerial() {
		return getProperty(PROPERTY_MODEM_SERIAL, String.class);
	}
	void updateBalance(BigDecimal amount, String confirmationCode, Date timestamp, String method) {
		setBalanceAmount(amount);
		setBalanceConfirmationCode(confirmationCode);
		setBalanceDateTime(timestamp);
		setBalanceUpdateMethod(method);
	}
	
	protected CService cService;
	protected EventBus eventBus;
	protected PaymentJobProcessor requestJobProcessor;
	protected PaymentJobProcessor responseJobProcessor;
	protected AccountDao accountDao;
	protected ClientDao clientDao;
	protected TargetDao targetDao;
	protected IncomingPaymentDao incomingPaymentDao;
	protected OutgoingPaymentDao outgoingPaymentDao;
	protected LogMessageDao logDao;
	protected ContactDao contactDao;
	private PersistableSettings settings;
	
	protected Logger log = FrontlineUtils.getLogger(this.getClass());
	protected TargetAnalytics targetAnalytics;

	public Class<? extends ConfigurableService> getSuperType() {
		return PaymentService.class;
	}
	
	public void startService() throws PaymentServiceException {
		final CService cService = this.cService;
		queueRequestJob(new PaymentJob() {
			public void run() {
				try{
					cService.doSynchronized(new SynchronizedWorkflow<Object>() {
						public Object run() throws SMSLibDeviceException, IOException {
							updateStatus(PaymentStatus.CONFIGURE_STARTED);
							cService.getAtHandler().stkInit();
							updateStatus(PaymentStatus.CONFIGURE_COMPLETE);
							return null;
						}
					});
				} catch (Throwable t) {
					t.printStackTrace();
					logDao.error(t.getClass().getSimpleName() + " in configureModem()", t);
					updateStatus(PaymentStatus.ERROR);
				}
			}
		});
	}

	@SuppressWarnings("rawtypes")
	public void notify(final FrontlineEventNotification notification) {
		if(notification instanceof EntitySavedNotification) {
			final Object entity = ((EntitySavedNotification) notification).getDatabaseEntity();
			if (entity instanceof FrontlineMessage) {
				final FrontlineMessage message = (FrontlineMessage) entity;
				processMessage(message);
			}
		}
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

	public void stopService() {
		eventBus.unregisterObserver(this);
		requestJobProcessor.stop();
		responseJobProcessor.stop();
	}
	
	protected <T extends Object> T getProperty(String key, Class<T> clazz) {
		return PersistableSettings.getPropertyValue(getPropertiesStructure(), settings, key, clazz);
	}
	
	/**
	 * Sets a property in {@link #settings}.
	 * @param key
	 * @param value
	 */
	protected void setProperty(String key, Object value) {
		this.settings.set(key, value);
	}

	public String getPin() {
		return getProperty(PROPERTY_PIN, String.class);
	}

	public void registerToEventBus(final EventBus eventBus) {
		if (eventBus != null) {
			this.eventBus = eventBus;
			this.eventBus.registerObserver(this);
		}
	}

	/** @return the settings attached to this instance. */
	public PersistableSettings getSettings() {
		return settings;
	}

	public void setPin(final String pin) {
		setProperty(PROPERTY_PIN, pin);
	}

	public void setCService(final CService cService) {
		this.cService = cService;
	}
	
//	public void setBalanceDispatcher(BalanceDispatcher balanceDispatcher) {
//		this.balanceDispatcher = balanceDispatcher;
//	}
	
	public void initDaosAndServices(final PaymentViewPluginController pluginController) {
		this.accountDao = pluginController.getAccountDao();
		this.clientDao = pluginController.getClientDao();
		this.outgoingPaymentDao = pluginController.getOutgoingPaymentDao();
		this.targetDao = pluginController.getTargetDao();
		this.incomingPaymentDao = pluginController.getIncomingPaymentDao();
		this.targetAnalytics = pluginController.getTargetAnalytics();
		this.logDao = pluginController.getLogMessageDao();
		this.contactDao = pluginController.getUiGeneratorController().getFrontlineController().getContactDao();
		
		this.registerToEventBus(
			pluginController.getUiGeneratorController().getFrontlineController().getEventBus()
		);
		
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
	
	public void updateStatus(PaymentStatus sending) {
		if (eventBus != null){
			eventBus.notifyObservers(new PaymentStatusEventNotification(sending));
		}
	}
	
	public void setSettings(PersistableSettings settings) {
		this.settings = settings;
	}

	public StructuredProperties getPropertiesStructure() {
		StructuredProperties p = new StructuredProperties();
		p.put(PROPERTY_PIN, "");
		p.put(PROPERTY_BALANCE_CONFIRMATION_CODE, "");
		p.put(PROPERTY_BALANCE_AMOUNT, new BigDecimal("0"));
		p.put(PROPERTY_BALANCE_DATE_TIME, 0L);
		p.put(PROPERTY_BALANCE_UPDATE_METHOD, "");
		p.put(PROPERTY_MODEM_SERIAL, "");
		return p;
	}
	
	abstract Date getTimePaid(FrontlineMessage message);
	abstract boolean isMessageTextValid(String message);
	abstract Account getAccount(FrontlineMessage message);
	abstract String getPaymentBy(FrontlineMessage message);
	protected abstract boolean isValidBalanceMessage(FrontlineMessage message);
}