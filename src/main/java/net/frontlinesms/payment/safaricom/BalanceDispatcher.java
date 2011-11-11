package net.frontlinesms.payment.safaricom;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import net.frontlinesms.data.domain.FrontlineMessage;

public class BalanceDispatcher {
	private Queue<MpesaPaymentService> queue = new LinkedList<MpesaPaymentService>();
	private List<FrontlineMessage> ignoredBalanceMessageList = new ArrayList<FrontlineMessage>();
	
	private static BalanceDispatcher INSTANCE = new BalanceDispatcher();
	public static BalanceDispatcher getInstance() {return INSTANCE;}
	private BalanceDispatcher(){}
	
	public void queuePaymentService(MpesaPaymentService paymentService) {
		queue.add(paymentService);
	}
	
	public Queue<MpesaPaymentService> getQueue(){
		return queue;
	}
	
	public void notify(FrontlineMessage message) {
		MpesaPaymentService ps;
		if (!ignoredBalanceMessageList.contains(message)){
			if (!queue.isEmpty()) {
				if (message.getEndpointId()!=null){
					for(Iterator<MpesaPaymentService> psIterator = queue.iterator(); psIterator.hasNext(); ) {
						ps = psIterator.next();
						if (message.getEndpointId().equals(ps.getSettings().getPsSmsModemSerial())){
							ps.finaliseBalanceProcessing(message);
							addToIgnoredBalanceMessageList(message);
							queue.remove(ps);
							break;
						}
					}
				}
			}
		}
	}
	
	public void addToIgnoredBalanceMessageList(FrontlineMessage message) {
		if (ignoredBalanceMessageList.contains(message)) return;
		ignoredBalanceMessageList.add(message);
	}
	
	public boolean remove(AbstractPaymentService paymentService) {
		return queue.remove(paymentService);
	}
}
