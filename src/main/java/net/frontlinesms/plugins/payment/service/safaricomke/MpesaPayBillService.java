package net.frontlinesms.plugins.payment.service.safaricomke;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.frontlinesms.data.domain.FrontlineMessage;
import net.frontlinesms.plugins.payment.service.PaymentServiceException;
import net.frontlinesms.plugins.payment.service.ui.PaymentServiceUiActionHandler;
import net.frontlinesms.serviceconfig.ConfigurableServiceProperties;
import net.frontlinesms.ui.UiGeneratorController;

import org.creditsms.plugins.paymentview.data.domain.Account;
import org.creditsms.plugins.paymentview.data.domain.OutgoingPayment;

@ConfigurableServiceProperties(name="MPESA Kenya PayBill", icon="/icons/mpesa_ke_paybill.png")
public class MpesaPayBillService extends MpesaPaymentService {
	private static final String DATETIME_PATTERN = "d/M/yy 'at' hh:mm a";
	
	private static final String PAYBILL_REGEX = "(\\w+) Confirmed.\\s+"
			+ "on (((?:[1-2]?[1-9]|[1-2]0|3[0-1])/(?:[1-9]|1[0-2])/\\d\\d) at ((1?\\d:[0-5]\\d) (AM|PM)))\\s+"
			+ "Ksh([,|\\d]+(|.[\\d]{2})) received from ([\\w\\s]+) (254[\\d]{9})\\.\\s+"
			+ "Account Number (.*\\S)\\s+"
			+ "New Utility balance is Ksh([,|\\d]+(|.[\\d]{2}))";	

	
	public boolean isOutgoingPaymentEnabled() {
		return super.isOutgoingPaymentEnabled();
	}
	
	@Override
	protected boolean isValidBalanceMessage(FrontlineMessage message) {
		return message.getTextContent().matches(PAYBILL_REGEX);
	}
	
	@Override
	protected void processBalance(FrontlineMessage message){
	}
	
	private String getMatch(FrontlineMessage message, int groupIndex) {
		Pattern paybillPattern = Pattern.compile(PAYBILL_REGEX);
		Matcher matcher = paybillPattern.matcher(message.getTextContent());
		matcher.matches();
		return matcher.group(groupIndex);
	}
	
	private String getAccountNumber(FrontlineMessage message) {
		return getMatch(message, 11);
	}
	
	@Override
	Account getAccount(FrontlineMessage message) {
		return accountDao.getAccountByAccountNumber(getAccountNumber(message));
	}

	@Override
	String getPaymentBy(FrontlineMessage message) {
		return getMatch(message, 9);
	}
	
	@Override
	String getPhoneNumber(FrontlineMessage message) {
		return "+" + getMatch(message, 10);
	}
	
	@Override
	String getNotes(FrontlineMessage message) {
		return getAccountNumber(message);
	}

	@Override
	Date getTimePaid(FrontlineMessage message) {
		String datetime = getMatch(message, 2);
		Date date = null;
		try {
			date = new SimpleDateFormat(DATETIME_PATTERN).parse(datetime);
		} catch (ParseException e) {
			e.printStackTrace();
		}
		return date;
	}

	@Override
	boolean isMessageTextValid(String messageText) {
		return messageText.matches(PAYBILL_REGEX);
	}
	
	public void makePayment(OutgoingPayment op) throws PaymentServiceException {
		throw new PaymentServiceException("Making payments is not possible with a PayBill account.");
	}
	
	public PaymentServiceUiActionHandler getServiceActionUiHandler(UiGeneratorController ui) {
		return null;
	}

	@Override
	public String getName() {
		return "M-PESA Kenya: Paybill Service";
	}
}
