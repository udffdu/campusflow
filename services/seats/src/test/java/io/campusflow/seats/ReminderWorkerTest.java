package io.campusflow.seats;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.campusflow.seats.http.DQueryClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReminderWorkerTest {
    private final UUID reservation=UUID.randomUUID(),user=UUID.randomUUID(),roomId=UUID.randomUUID(),seatId=UUID.randomUUID();

    @Test void earlyReminderShowsReadableRoomSeatAndShanghaiPeriod(){
        var notification=ReminderWorker.notification(delivery(ReminderStore.START),room(),seat());
        assertEquals("预约即将开始｜A101 · S01",notification.title());
        assertEquals("教室：A101研讨室（A101）\n座位：靠窗01号座位（S01）\n"
                +"时间：2026年10月30日 14:00–15:00（上海时间）\n\n"
                +"预约将在 15 分钟内开始，请按时到达，并使用教室当日签到码完成签到。",notification.body());
    }

    @Test void lateReminderKeepsTheSameReservationDetailsAndClearAction(){
        var notification=ReminderWorker.notification(delivery(ReminderStore.LATE),room(),seat());
        assertEquals("尚未签到｜A101 · S01",notification.title());
        assertTrue(notification.body().contains("教室：A101研讨室（A101）"));
        assertTrue(notification.body().contains("座位：靠窗01号座位（S01）"));
        assertTrue(notification.body().contains("预约已开始超过 10 分钟，目前仍未签到"));
    }

    @Test void noShowNoticeExplainsCancellationReleaseAndViolation(){
        var notification=ReminderWorker.notification(delivery(ReminderStore.NO_SHOW),room(),seat());
        assertEquals("预约已因未签到取消｜A101 · S01",notification.title());
        assertTrue(notification.body().contains("教室：A101研讨室（A101）"));
        assertTrue(notification.body().contains("座位：靠窗01号座位（S01）"));
        assertTrue(notification.body().contains("取消预约、释放座位，并记录一次未签到违约"));
    }

    private ReminderStore.Delivery delivery(String type){
        return new ReminderStore.Delivery(UUID.randomUUID(),type,reservation,user,roomId,seatId,
                Instant.parse("2026-10-30T06:00:00Z"),Instant.parse("2026-10-30T07:00:00Z"),1);
    }
    private DQueryClient.Resource room(){return resource(roomId,"ROOM",UUID.randomUUID(),"A101","A101研讨室");}
    private DQueryClient.Resource seat(){return resource(seatId,"SEAT",roomId,"S01","靠窗01号座位");}
    private DQueryClient.Resource resource(UUID id,String type,UUID parent,String code,String name){
        return new DQueryClient.Resource(id,type,parent,UUID.randomUUID(),code,name,true,
                type.equals("ROOM")?ReservationAccessPolicy.BookingScope.CAMPUS:null,
                new OpeningSchedule(List.of()),JsonNodeFactory.instance.arrayNode(),Set.of(),1);
    }
}
