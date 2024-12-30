package com.krowfeather.customer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krowfeather.customer.entity.Customer;
import com.krowfeather.customer.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService customerService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }
    @PostMapping("/login")
    public Integer login(@RequestBody Customer customer) {
        Subject subject = SecurityUtils.getSubject();
        AuthenticationToken token = new UsernamePasswordToken(customer.getUsername(), customer.getPassword());
        try{
            subject.login(token);
        }catch (Exception e) {
            log.error(e.getMessage());
            return -1;
        }
        Customer customer1 = customerService.getByUsername(customer.getUsername());
        return customer1.getId();
    }

    @GetMapping("/get_proposal")
    public String getProposal(@RequestParam Integer id,@RequestParam String depart, @RequestParam String destination) {
        this.customerService.getProposal(id,depart, destination);
        return "success";
    }

    @PostMapping("/withdraw")
    public String withdraw(@RequestParam Integer pid) {
        this.customerService.withdraw(pid);
        return "withdraw success";
    }

    @PostMapping("/accept")
    public String accept(@RequestParam Integer pid) {
        this.customerService.accept(pid);
        return "accept success";
    }

    @GetMapping("/all_waiting_proposal")
    public String allWaitingProposal(@RequestParam Integer id) {
        return this.customerService.allWaitingProposal(id);
    }


    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "send_proposal.queue1",durable = "true"),
            exchange = @Exchange(value = "kf.fanout",type = ExchangeTypes.FANOUT)
    ))
    public void listen(Message message) throws InterruptedException {
        Jackson2JsonMessageConverter jackson2JsonMessageConverter = new Jackson2JsonMessageConverter();
        Thread.sleep(1000);
        try {
            Map<String,Object> data = (Map<String, Object>) jackson2JsonMessageConverter.fromMessage(message);
            System.out.println("RECEIVED PROPOSAL:"+data);
            String proposal = (String) data.get("proposal");
            Map proposal1 = objectMapper.readValue(proposal, Map.class);
            Integer cid = (Integer) proposal1.get("cid");
            System.out.println(cid);
            SseEmitter sseEmitter = sseEmitterMap.get(cid);
            sseEmitter.send(SseEmitter.event().data(proposal));
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "proposal_get.queue1",durable = "true"),
            exchange = @Exchange(value = "kf.fanout",type = ExchangeTypes.FANOUT)
    ))
    public void listen1(Message message) throws InterruptedException {
        Jackson2JsonMessageConverter jackson2JsonMessageConverter = new Jackson2JsonMessageConverter();
        Thread.sleep(1000);
        try {
            List<Map<String, Object>> data = (List<Map<String, Object>>) jackson2JsonMessageConverter.fromMessage(message);
            System.err.println("RECEIVED PROPOSAL:"+data);
            if(data.isEmpty()) {
                return;
            }
            SseEmitter sseEmitter = sseWaitingProposalEmitterMap.get(data.get(0).get("cid"));
            sseEmitter.send(SseEmitter.event().data(data));
        }catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    private final Map<Integer,SseEmitter> sseEmitterMap = new ConcurrentHashMap<>();
    private final Map<Integer,SseEmitter> sseWaitingProposalEmitterMap = new ConcurrentHashMap<>();
    @GetMapping("/subscribe/{id}")
    public SseEmitter subscribe(@PathVariable Integer id) {
        SseEmitter sseEmitter = new SseEmitter(0L);
        sseEmitterMap.put(id,sseEmitter);
        sseEmitter.onCompletion(() -> sseEmitterMap.remove(id));
        sseEmitter.onError(e->log.error("Error in SSE stream", e));
        return sseEmitter;
    }
    @GetMapping("/waiting_proposal_subscribe/{id}")
    public SseEmitter waitingProposalSubscribe(@PathVariable Integer id) {
        SseEmitter sseEmitter = new SseEmitter(0L);
        sseWaitingProposalEmitterMap.put(id,sseEmitter);
        sseEmitter.onCompletion(() -> sseWaitingProposalEmitterMap.remove(id));
        sseEmitter.onError(e->log.error("Error in SSE stream", e));
        return sseEmitter;
    }

}