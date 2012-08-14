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
import net.frontlinesms.messaging.sms.modem.SmsModem;
import net.frontlinesms.plugins.payment.service.PaymentJob;
import net.frontlinesms.plugins.payment.service.PaymentJobProcessor;
import net.frontlinesms.plugins.payment.service.PaymentService;
import net.frontlinesms.plugins.payment.service.PaymentServiceException;
import net.frontlinesms.plugins.payment.service.PaymentStatus;
import net.frontlinesms.serviceconfig.ConfigurableService;
import net.frontlinesms.serviceconfig.PasswordString;
import net.frontlinesms.serviceconfig.SmsModemReference;
import net.frontlinesms.serviceconfig.StructuredProperties;

import org.apache.log4j.Logger;
import org.creditsms.plugins.paymentview.PaymentViewPluginController;
import org.creditsms.plugins.paymentview.analytics.TargetAnalytics;
import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.IncomingPayment;
import org.creditsms.plugins.paymentview.data.repository.AccountDao;
import org.creditsms.plugins.paymentview.data.repository.ClientDao;
import org.creditsms.plugins.paymentview.data.repository.IncomingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.LogMessageDao;
import org.creditsms.plugins.paymentview.data.repository.OutgoingPaymentDao;
import org.creditsms.plugins.paymentview.data.repository.PaymentServiceSettingsDao;
import org.creditsms.plugins.paymentview.data.repository.TargetDao;
import org.smslib.CService;
import org.smslib.SMSLibDeviceException;
import org.smslib.handler.ATHandler.SynchronizedWorkflow;

public abstract class AbstractPaymentService implements PaymentService, EventObserver {
//> STATIC CONSTANTS
	/** Prefix attached to every property name. */
	private static final String PROPERTY_PREFIX = "plugins.payment.mpesa.";

	protected static final String PROPERTY_PIN = PROPERTY_PREFIX + "pin";
	protected static final String PROPERTY_BALANCE_CONFIRMATION_CODE = PROPERTY_PREFIX + "balance.confirmation";
	protected static final String PROPERTY_BALANCE_AMOUNT = PROPERTY_PREFIX + "balance.amount";
	protected static final String PROPERTY_BALANCE_DATE_TIME = PROPERTY_PREFIX + "balance.timestamp";
	protected static final String PROPERTY_BALANCE_UPDATE_METHOD = PROPERTY_PREFIX + "balance.update.method";
	protected static final String PROPERTY_MODEM_SERIAL = PROPERTY_PREFIX + "modem.serial";
	protected static final String PROPERTY_SIM_IMSI = PROPERTY_PREFIX + "sim.imsi";
	protected static final String PROPERTY_OUTGOING_ENABLED = PROPERTY_PREFIX + "outgoing.enabled";
	protected static final String PROPERTY_BALANCE_ENABLED = PROPERTY_PREFIX + "balance.enabled";
	
//> INSTANCE PROPERTIES
	protected Logger log = FrontlineUtils.getLogger(this.getClass());
	protected TargetAnalytics targetAnalytics;
	protected SmsModem smsModem;
	protected EventBus eventBus;
	protected PaymentJobProcessor outgoingJobProcessor;
	protected PaymentJobProcessor incomingJobProcessor;
	protected AccountDao accountDao;
	protected ClientDao clientDao;
	protected TargetDao targetDao;
	protected IncomingPaymentDao incomingPaymentDao;
	protected OutgoingPaymentDao outgoingPaymentDao;
	protected LogMessageDao logDao;
	protected PaymentServiceSettingsDao settingsDao;
	protected ContactDao contactDao;
	private PersistableSettings settings;
	private PaymentViewPluginController pluginController;

//> CONSTRUCTORS AND INITIALISERS
	public void init(PaymentViewPluginController pluginController) throws PaymentServiceException {
		setSmsModem(pluginController);
		this.pluginController = pluginController;
		this.accountDao = pluginController.getAccountDao();
		this.clientDao = pluginController.getClientDao();
		this.outgoingPaymentDao = pluginController.getOutgoingPaymentDao();
		this.targetDao = pluginController.getTargetDao();
		this.incomingPaymentDao = pluginController.getIncomingPaymentDao();
		this.targetAnalytics = pluginController.getTargetAnalytics();
		this.logDao = pluginController.getLogMessageDao();
		this.contactDao = pluginController.getUiGeneratorController().getFrontlineController().getContactDao();
		this.settingsDao = pluginController.getPaymentServiceSettingsDao();
		
		this.eventBus = pluginController.getEventBus();
		eventBus.registerObserver(this);
		
		this.incomingJobProcessor = new PaymentJobProcessor(this);
		this.incomingJobProcessor.start();
		
		this.outgoingJobProcessor = new PaymentJobProcessor(this);
		this.outgoingJobProcessor.start();

		setCheckBalanceEnabled(smsModem.getCService().getAtHandler().supportsStk());
	}
	
	public void setLog(Logger log) {
		this.log = log;
	}

	protected void initIfRequired() throws SMSLibDeviceException, IOException {
		// For now, we assume that init is always required.  If there is a clean way
		// of identifying when it is and is not, we should perhaps implement this.
		smsModem.getCService().getAtHandler().stkInit();
	}
	
//> INSTANCE (TRANSIENT) ACCESSORS
	/** @return the settings attached to this instance. */
	public PersistableSettings getSettings() {
		return settings;
	}
	public void setSettings(PersistableSettings settings) {
		this.settings = settings;
	}
	public boolean isRestartRequired(PersistableSettings newSettings) {
		// return true if any user-modified settings have changed
		return hasChanged(newSettings, PROPERTY_MODEM_SERIAL) ||
				hasChanged(newSettings, PROPERTY_PIN) ||
				hasChanged(newSettings, PROPERTY_OUTGOING_ENABLED) ||
				hasChanged(newSettings, PROPERTY_BALANCE_ENABLED);
	}
	
	private boolean hasChanged(PersistableSettings newSettings, String propertyKey) {
		return !newSettings.get(propertyKey).equals(settings.get(propertyKey));
	}

	private void setSmsModem(PaymentViewPluginController pluginController) throws PaymentServiceException {
		String serial = getModemSerial();
		for(SmsModem m : pluginController.getUiGeneratorController().getFrontlineController().getSmsServiceManager().getSmsModems()) {
			if(m.getSerial().equals(serial) && m.isConnected()) {
				smsModem = m;
				return;
			}
		}
		throw new PaymentServiceException("No CService found for serial: " + serial);
	}
	
	public void setSmsModem(SmsModem smsModem) {
		this.smsModem = smsModem;
	}
	
//> PERSISTENT PROPERTY ACCESSORS
	public String getBalanceConfirmationCode() {
		return getProperty(PROPERTY_BALANCE_CONFIRMATION_CODE, "");
	}
	public void setBalanceAmount(BigDecimal balanceAmount) {
		setProperty(PROPERTY_BALANCE_AMOUNT, balanceAmount);
	}
	public BigDecimal getBalanceAmount() {
		return getProperty(PROPERTY_BALANCE_AMOUNT, new BigDecimal("0"));
	}
	public void setBalanceConfirmationCode(String balanceConfirmationCode) {
		setProperty(PROPERTY_BALANCE_CONFIRMATION_CODE, balanceConfirmationCode);
	}
	public Date getBalanceDateTime() {
		return new Date(getProperty(PROPERTY_BALANCE_DATE_TIME, 0L));
	}
	public void setBalanceDateTime(Date balanceDateTime) {
		setProperty(PROPERTY_BALANCE_DATE_TIME, balanceDateTime.getTime());
	}
	public String getBalanceUpdateMethod() {
		return getProperty(PROPERTY_BALANCE_UPDATE_METHOD, "");
	}
	public void setBalanceUpdateMethod(String balanceUpdateMethod) {
		setProperty(PROPERTY_BALANCE_UPDATE_METHOD, balanceUpdateMethod);
	}
	public String getModemSerial() {
		return getProperty(PROPERTY_MODEM_SERIAL, SmsModemReference.class).getSerial();
	}
	public String getPin() {
		return getProperty(PROPERTY_PIN, PasswordString.class).getValue();
	}
	public void setPin(final String pin) {
		setProperty(PROPERTY_PIN, pin);
	}
	
	public boolean isOutgoingPaymentEnabled() {
		return getProperty(PROPERTY_OUTGOING_ENABLED, Boolean.class);
	}

	public void setOutgoingPaymentEnabled(boolean outgoingEnabled) {
		this.settings.set(PROPERTY_OUTGOING_ENABLED, outgoingEnabled);
	}
	
	public boolean isCheckBalanceEnabled() {
		return getProperty(PROPERTY_BALANCE_ENABLED, Boolean.class);
	}

	public void setCheckBalanceEnabled(boolean checkBalanceEnabled) {
		this.settings.set(PROPERTY_BALANCE_ENABLED, checkBalanceEnabled);
	}
	
	public String getSimImsi() {
		return getProperty(PROPERTY_SIM_IMSI, "");
	}
	
	public void setSimImsi(String simImsi) {
		setProperty(PROPERTY_SIM_IMSI, simImsi);
	}
	
	void updateBalance(BigDecimal amount, String confirmationCode, Date timestamp, String method) {
		setBalanceAmount(amount);
		setBalanceConfirmationCode(confirmationCode);
		setBalanceDateTime(timestamp);
		setBalanceUpdateMethod(method);
		settingsDao.updateServiceSettings(settings);
	}

//> CONFIGURABLE SERVICE METHODS
	public Class<? extends ConfigurableService> getSuperType() {
		return PaymentService.class;
	}
	
	public void startService() throws PaymentServiceException {
		if(smsModem == null) throw new PaymentServiceException("Cannot start payment service with null CService.");
		
		// if this is the first start, set and save the SIM IMSI
		if(getSimImsi().length()==0) {
			setSimImsi(smsModem.getImsiNumber());
			settingsDao.updateServiceSettings(settings);
		}
		
		final CService cService = smsModem.getCService();
		
		queueOutgoingJob(new PaymentJob() {
			public void run() {
				try {
					cService.doSynchronized(new SynchronizedWorkflow<Object>() {
						public Object run() throws SMSLibDeviceException, IOException {
							updateStatus(PaymentStatus.CONFIGURE_STARTED);
							if(cService.supportsStk()) {
								cService.getAtHandler().stkInit();
							}
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

	public void stopService() {
		eventBus.unregisterObserver(this);
		incomingJobProcessor.stop();
		outgoingJobProcessor.stop();
	}

	public StructuredProperties getPropertiesStructure() {
		StructuredProperties p = new StructuredProperties();
		p.put(PROPERTY_PIN, new PasswordString(""));
		p.put(PROPERTY_MODEM_SERIAL, new SmsModemReference(null));
		p.put(PROPERTY_OUTGOING_ENABLED, true);
		p.put(PROPERTY_BALANCE_ENABLED, true);
		return p;
	}

//> EVENT OBSERVER METHODS
	@SuppressWarnings("rawtypes")
	public void notify(final FrontlineEventNotification notification) {
		if(notification instanceof EntitySavedNotification) {
			final Object entity = ((EntitySavedNotification) notification).getDatabaseEntity();
			if (entity instanceof FrontlineMessage) {
				final FrontlineMessage message = (FrontlineMessage) entity;
				try {
					processMessage(message);
				} catch(Exception ex) {
					log.warn("Exception thrown while attempting to process message in payment service '" + this + "'", ex);
				}
			}
		}
	}
	
//> ABSTRACT SAFARICOM SERVICE METHODS
	protected abstract void processMessage(final FrontlineMessage message);
	abstract Date getTimePaid(FrontlineMessage message);
	abstract boolean isMessageTextValid(String message);
	abstract Account getAccount(FrontlineMessage message);
	abstract String getName();
	abstract String getNotes(FrontlineMessage message);
	abstract String getPaymentBy(FrontlineMessage message);
	protected abstract boolean isValidBalanceMessage(FrontlineMessage message);

//> UTILITY METHODS
	void queueIncomingJob(PaymentJob job) {
		incomingJobProcessor.queue(job);
	}
	void queueOutgoingJob(PaymentJob job) {
		outgoingJobProcessor.queue(job);
	}
	
	/** Gets a property from {@link #settings}.  This should be used for all non-user values. */
	<T extends Object> T getProperty(String key, T defaultValue) {
		return PersistableSettings.getPropertyValue(settings, key, defaultValue);
	}
	/** Gets a property from {@link #settings}. */
	<T extends Object> T getProperty(String key, Class<T> clazz) {
		return PersistableSettings.getPropertyValue(getPropertiesStructure(), settings, key, clazz);
	}
	/** Sets a property in {@link #settings}. */
	void setProperty(String key, Object value) {
		this.settings.set(key, value);
	}

	protected String getFirstMatch(final FrontlineMessage message, final String regexMatcher) {
		return getFirstMatch(message.getTextContent(), regexMatcher);
	}	
	protected String getFirstMatch(final String string, final String regexMatcher) {
		final Matcher matcher = Pattern.compile(regexMatcher).matcher(string);
		matcher.find();
		return matcher.group();
	}

	void registerToEventBus(final EventBus eventBus) {
		if (eventBus != null) {
			this.eventBus = eventBus;
			this.eventBus.registerObserver(this);
		}
	}
	
	void updateStatus(PaymentStatus status) {
		pluginController.updateStatusBar(status.toString());
	}
	
	void reportPaymentFromNewClient(IncomingPayment payment){
		pluginController.reportPaymentByNewClient(payment.getPaymentBy(), payment.getAmountPaid());
	}
	
//> CORE JAVA OVERRIDES
	public String toString() {
		return getClass().getSimpleName() + "::" +
				(this.settings!=null? this.settings.getId(): "unsaved");
	}
}