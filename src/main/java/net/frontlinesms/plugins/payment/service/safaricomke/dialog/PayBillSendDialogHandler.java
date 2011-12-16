package net.frontlinesms.plugins.payment.service.safaricomke.dialog;

import java.math.BigDecimal;

import net.frontlinesms.plugins.payment.service.safaricomke.MpesaPersonalService;
import net.frontlinesms.ui.UiGeneratorController;

import org.creditsms.plugins.paymentview.ui.handler.AuthorisationCodeHandler;
import org.creditsms.plugins.paymentview.ui.handler.base.BaseDialog;

public class PayBillSendDialogHandler extends BaseDialog {
	private static final String AMOUNT_TO_TRANSFER = "amountToTransfer";
	private static final String BUSINESS_NAME = "businessName";
	private static final String BUSINESS_NO = "businessNo";
	private static final String ACCOUNT_NO = "accountNo";
	private static final String XML_CONFIGURE_ACCOUNT = "/ui/plugins/payment/service/safaricomke/dialog/dlgPayBillSend.xml";
	private final MpesaPersonalService service;
	private Object tfAmountToTransfer;
	private Object tfAccountNo;
	private Object tfBusinessNo;
	private Object tfBusinessName;

	public PayBillSendDialogHandler(UiGeneratorController ui, MpesaPersonalService service) {
		super(ui);
		this.service = service;
		init();
		refresh();
	}

	private void init() {
		dialogComponent = ui.loadComponentFromFile(XML_CONFIGURE_ACCOUNT, this);
		tfAmountToTransfer = ui.find(dialogComponent, AMOUNT_TO_TRANSFER);
		tfAccountNo = ui.find(dialogComponent, ACCOUNT_NO);
		tfBusinessNo = ui.find(dialogComponent, BUSINESS_NO);
		tfBusinessName = ui.find(dialogComponent, BUSINESS_NAME);
	}

	public void sendPayment() {
		new AuthorisationCodeHandler(ui).showAuthorizationCodeDialog(this, "sendPaymentToPaymentService");
	}

	public void sendPaymentToPaymentService() {
		String businessName = ui.getText(tfBusinessName);
 		String businessNo = ui.getText(tfBusinessNo);
 		String accountNo = ui.getText(tfAccountNo);
 		BigDecimal amountToTransfer = BigDecimal.ZERO;
 		try{
 			amountToTransfer = new BigDecimal(ui.getText(tfAmountToTransfer));
 		}catch(NumberFormatException e){
 			ui.alert("Please enter a valid amount");
 			return;
 		}
		service.sendAmountToPaybillAccount(businessName, businessNo, accountNo, amountToTransfer);
		removeDialog();
	}

	@Override
	protected void refresh() {
	}
}