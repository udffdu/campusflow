package io.campusflow.seats;

import io.campusflow.seats.http.DQueryClient;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Scanner and dispatcher are safe to run on multiple replicas; persistence supplies dedupe and leases. */
public final class ReminderWorker {
    private static final ZoneId CAMPUS=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy年M月d日",Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter DATE_TIME=DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm",Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm",Locale.SIMPLIFIED_CHINESE);
    private final ReminderStore store;
    private final DQueryClient d;
    private final Duration lease,retryBase,retryMax;
    private final int scanLimit,deliveryLimit;
    public ReminderWorker(ReminderStore store,DQueryClient d,Duration lease,Duration retryBase,Duration retryMax,
                          int scanLimit,int deliveryLimit){
        this.store=store;this.d=d;this.lease=lease;this.retryBase=retryBase;this.retryMax=retryMax;
        this.scanLimit=scanLimit;this.deliveryLimit=deliveryLimit;
    }
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString="${m1.reminders.delay:PT30S}",initialDelayString="${m1.reminders.initial-delay:PT5S}")
    public void runCycle(){
        store.scheduleDue(scanLimit);
        for(var event:store.claimDue(deliveryLimit,lease)){
            try{
                var call=d.call();
                var room=call.room(event.roomId());
                var seat=call.seat(event.seatId());
                call.reminder(notification(event,room,seat));
                store.delivered(event.eventId());
            }
            catch(Exception exception){store.retry(event,"D_UNAVAILABLE",retryBase,retryMax);}
        }
    }
    static DQueryClient.ReminderNotification notification(ReminderStore.Delivery event,DQueryClient.Resource room,DQueryClient.Resource seat){
        ZonedDateTime start=event.startAt().atZone(CAMPUS),end=event.endAt().atZone(CAMPUS);
        String period=start.toLocalDate().equals(end.toLocalDate())
                ?DATE.format(start)+" "+TIME.format(start)+"–"+TIME.format(end)
                :DATE_TIME.format(start)+" – "+DATE_TIME.format(end);
        boolean early=ReminderStore.START.equals(event.eventType());
        String title=(early?"预约即将开始｜":"尚未签到｜")+compact(room.code())+" · "+compact(seat.code());
        String action=early
                ?"预约将在 15 分钟内开始，请按时到达，并使用教室当日签到码完成签到。"
                :"预约已开始超过 10 分钟，目前仍未签到。请尽快到达，并使用教室当日签到码完成签到。";
        String body="教室："+label(room)+"\n座位："+label(seat)+"\n时间："+period+"（上海时间）\n\n"+action;
        return new DQueryClient.ReminderNotification("m1-reminder:"+event.eventId(),event.userId(),title,body,
                "SEAT_RESERVATION",event.reservationId().toString());
    }
    private static String label(DQueryClient.Resource resource){
        String name=oneLine(resource.name()),code=oneLine(resource.code());
        return name.equalsIgnoreCase(code)?code:name+"（"+code+"）";
    }
    private static String compact(String value){String clean=oneLine(value);return clean.length()<=36?clean:clean.substring(0,35)+"…";}
    private static String oneLine(String value){return value.replaceAll("[\\r\\n\\t]+"," ").strip();}
}
