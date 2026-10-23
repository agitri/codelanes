package com.acme.shop.mail;

import com.acme.shop.order.OrderPlaced;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class SendOrderMail {
    @EventListener
    public void on(OrderPlaced event) {
        System.out.println("Mail for order " + event.order().id());
    }
}
