package net.frontlinesms.plugins.payment.service.safaricomke;

import net.frontlinesms.plugins.payment.service.safaricomke.dialog.PayBillSendDialogHandler;
import net.frontlinesms.plugins.payment.service.ui.PaymentServiceUiActionHandler;
import net.frontlinesms.ui.UiGeneratorController;

public class MpesaPersonalActionUiHandler implements PaymentServiceUiActionHandler {
	private final MpesaPersonalService service;
	private final UiGeneratorController ui;
	
	public MpesaPersonalActionUiHandler(MpesaPersonalService service, UiGeneratorController ui) {
		this.service = service;
		this.ui = ui;
	}

	public boolean hasMenuItems() {
		return true;
	}

	public Object[] getMenuItems() {

		Object payBillMenuItem = createMenuitem("make PayBill payment", service.smsModem.getCService().getAtHandler().supportsStk());
		ui.setAction(payBillMenuItem, "launchPaybillWizard", payBillMenuItem, this);
		return new Object[] {
				payBillMenuItem 
		};
	}
	
	private Object createMenuitem(String text,
			boolean supportsStk) {
		Object m = ui.createMenuitem("", text);
		ui.setEnabled(m, supportsStk);
		return m;
	}

	public void launchPaybillWizard() {
		new PayBillSendDialogHandler(ui, service).showDialog();
	}
	
}
