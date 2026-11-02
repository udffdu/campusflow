package io.campusflow.rooms;

import static io.campusflow.rooms.Models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApplicationService {
    static final List<Step> STEPS=List.of(new Step("院系初审","rooms.review"),new Step("教室终审","rooms.approve"));
    static final Set<String> STATES=Set.of("PENDING","PROCESSING","APPROVED","REJECTED","EXPIRED","CANCELLED","CONFLICT","RELEASING");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RemoteServices remote;
    private final ObjectMapper json;
    private final Clock clock;
    private final int approvalHours,maxHours,advanceDays;
    ApplicationService(JdbcTemplate db,TransactionTemplate tx,RemoteServices remote,ObjectMapper json,Clock clock,
                       @Value("${rooms.approval-hours}") int approvalHours,@Value("${rooms.max-hours}") int maxHours,
                       @Value("${rooms.advance-days}") int advanceDays) {
        this.db=db;this.tx=tx;this.remote=remote;this.json=json;this.clock=clock;
        if(approvalHours<1||approvalHours>168||maxHours<1||maxHours>16||advanceDays<1||advanceDays>365) throw new IllegalStateException("Invalid room application policy");
        this.approvalHours=approvalHours;this.maxHours=maxHours;this.advanceDays=advanceDays;
    }
    public Map<String,Object> policy() { return Map.of("steps",STEPS,"approvalHours",approvalHours,"maxHours",maxHours,"advanceDays",advanceDays,"minuteStep",30,"minLeadMinutes",15,"timezone","Asia/Shanghai"); }
    public Page<Application> list(Identity actor,String view,String status,int page,int size) {
        if(!Set.of("mine","review","manage").contains(view)||(!status.isBlank()&&!STATES.contains(status))) throw ApiException.bad("FILTER","筛选条件无效");
        var args=new ArrayList<Object>();String where;
        if(view.equals("mine")) {where="applicant_id=?";args.add(actor.id());}
        else {
            if(view.equals("manage")) require(actor,"rooms.manage");
            else if(!actor.can("rooms.review")&&!actor.can("rooms.approve")) throw ApiException.denied();
            where="true";
            if(!actor.admin()) {where+=" and department_id=?";args.add(actor.departmentId());}
            if(view.equals("review")) {
                where+=" and status='PENDING' and deadline>? and applicant_id<>? and not exists(select 1 from m2.application_history h where h.application_id=a.id and h.action='STEP_APPROVED' and h.actor_id=?) and (";
                args.add(ts(clock.instant()));args.add(actor.id());args.add(actor.id());
                var conditions=new ArrayList<String>();
                for(String permission:List.of("rooms.review","rooms.approve")) if(actor.can(permission)) {conditions.add("steps->current_step->>'permission'=?");args.add(permission);}
                where+=String.join(" or ",conditions)+")";
            }
        }
        if(!status.isBlank()){where+=" and status=?";args.add(status);}
        long total=db.queryForObject("select count(*) from m2.applications a where "+where,Long.class,args.toArray());
        var paged=new ArrayList<>(args);paged.add(size);paged.add((long)page*size);
        return new Page<>(db.query("select a.* from m2.applications a where "+where+" order by created_at desc,id limit ? offset ?",this::row,paged.toArray()),page,size,total);
    }
    public Detail detail(UUID id,Identity actor) {var app=get(id,false);readable(app,actor);return detail(app);}
    private Detail detail(Application app) {
        var history=db.query("select * from m2.application_history where application_id=? order by version",(r,n)->new History(r.getObject("id",UUID.class),r.getLong("version"),r.getString("action"),r.getObject("actor_id",UUID.class),r.getString("actor_name"),(Integer)r.getObject("step"),r.getString("comment"),r.getTimestamp("occurred_at").toInstant()),app.id());
        var events=db.query("select id,kind,status,attempts,last_error from m2.outbox where application_id=? order by created_at,id",(r,n)->new EventStatus(r.getObject("id",UUID.class),r.getString("kind"),r.getString("status"),r.getInt("attempts"),r.getString("last_error")),app.id());
        return new Detail(app,history,events);
    }
    public Detail create(Create input,Identity actor) {
        require(actor,"rooms.apply");
        return tx.execute(ignored->{
            // Serialize an applicant's deduplication and overlap checks across service instances.
            db.query("select pg_advisory_xact_lock(hashtextextended(?,0))",(RowCallbackHandler)r->{},"M2:applicant:"+actor.id());
            String hash=hash(input);var existing=db.query("select * from m2.applications where applicant_id=? and request_key=?",this::row,actor.id(),input.requestKey());
            if(!existing.isEmpty()) {
                String saved=db.queryForObject("select request_hash from m2.applications where id=?",String.class,existing.getFirst().id());
                if(!hash.equals(saved))throw ApiException.conflict("同一提交编号对应不同内容，请重新发起申请");
                return detail(existing.getFirst());
            }
            var room=remote.resource(input.roomId());validateResource(room,input.attendees(),input.startAt(),input.endAt());
            if(!actor.scope(room.departmentId()))throw ApiException.denied();
            Instant now=clock.instant();
            if(input.startAt().isBefore(now.plusSeconds(900))||input.startAt().isAfter(now.plus(Duration.ofDays(advanceDays))))throw ApiException.bad("TIME_RANGE","请至少提前 15 分钟，并在可申请天数范围内提交");
            if(db.queryForObject("select count(*) from m2.applications where applicant_id=? and room_id=? and status in ('PENDING','PROCESSING','APPROVED','RELEASING') and start_at<? and end_at>?",Long.class,actor.id(),room.id(),ts(input.endAt()),ts(input.startAt()))>0)throw ApiException.conflict("你在该教室已有重叠时段的申请，请先查看或撤销原申请");
            UUID id=UUID.randomUUID();Instant deadline=now.plus(Duration.ofHours(approvalHours));if(deadline.isAfter(input.startAt()))deadline=input.startAt();
            db.update("insert into m2.applications(id,applicant_id,applicant_name,department_id,room_id,room_name,resource_version,title,purpose,attendees,start_at,end_at,deadline,steps,status,request_key,request_hash,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'PENDING',?,?,?,?)",
                id,actor.id(),actor.displayName(),room.departmentId(),room.id(),room.name(),room.version(),input.title().trim(),input.purpose().trim(),input.attendees(),ts(input.startAt()),ts(input.endAt()),ts(deadline),encode(STEPS),input.requestKey(),hash,ts(now),ts(now));
            var app=get(id,false);record(app,"SUBMITTED",actor,null,"提交申请");return detail(app);
        });
    }
    public Detail decide(UUID id,Decision input,Identity actor) {
        return tx.execute(ignored->{
            var app=get(id,true);readable(app,actor);version(app,input.expectedVersion());
            if(!app.status().equals("PENDING"))throw ApiException.conflict("申请已不在待审批状态，请刷新");
            if(input.step()!=app.currentStep())throw ApiException.conflict("审批节点已变化，请刷新");
            require(actor,app.steps().get(app.currentStep()).permission());scope(app,actor);
            if(actor.id().equals(app.applicantId())||db.queryForObject("select count(*) from m2.application_history where application_id=? and action='STEP_APPROVED' and actor_id=?",Long.class,id,actor.id())>0)throw ApiException.denied();
            if(!clock.instant().isBefore(app.deadline())) {change(app,"EXPIRED","EXPIRED",null,null,"审批截止时间已到");return detail(get(id,false));}
            if(input.decision().equals("REJECT"))change(app,"REJECTED","REJECTED",actor,app.currentStep(),input.comment());
            else {
                int next=app.currentStep()+1;
                db.update("update m2.applications set current_step=?,operation_version=?,next_attempt_at=? where id=?",next,next==app.steps().size()?1:0,ts(clock.instant()),id);
                change(app,next==app.steps().size()?"PROCESSING":"PENDING","STEP_APPROVED",actor,app.currentStep(),input.comment());
            }
            return detail(get(id,false));
        });
    }
    public Detail cancel(UUID id,Change input,Identity actor) {
        return tx.execute(ignored->{
            var app=get(id,true);readable(app,actor);version(app,input.expectedVersion());
            if(!app.applicantId().equals(actor.id())){require(actor,"rooms.manage");scope(app,actor);}
            if(!Set.of("PENDING","PROCESSING","APPROVED").contains(app.status()))throw ApiException.conflict("当前状态不能撤销，请刷新记录");
            if(!clock.instant().isBefore(app.startAt())&&app.status().equals("APPROVED"))throw ApiException.conflict("使用已开始，不能自行撤销已批准申请");
            if(app.status().equals("PENDING"))change(app,"CANCELLED","CANCELLED",actor,null,input.comment());
            else release(app,"CANCELLED",actor,input.comment());
            return detail(get(id,false));
        });
    }
    public Detail retry(UUID id,Change input,Identity actor) {
        require(actor,"rooms.manage");
        return tx.execute(ignored->{
            var app=get(id,true);scope(app,actor);version(app,input.expectedVersion());
            if(!Set.of("PROCESSING","RELEASING").contains(app.status())&&db.queryForObject("select count(*) from m2.outbox where application_id=? and status='DEAD'",Long.class,id)==0)throw ApiException.conflict("该申请没有可恢复的任务");
            db.update("update m2.applications set next_attempt_at=?,needs_attention=false,attempts=0 where id=?",ts(clock.instant()),id);
            db.update("update m2.outbox set status='PENDING',next_attempt_at=?,attempts=0,last_error=null where application_id=? and status='DEAD'",ts(clock.instant()),id);
            change(app,app.status(),"RETRY_REQUESTED",actor,null,input.comment());return detail(get(id,false));
        });
    }
    public void expire() {
        var ids=db.queryForList("select id from m2.applications where status in ('PENDING','PROCESSING') and deadline<=? order by deadline limit 50",UUID.class,ts(clock.instant()));
        for(UUID id:ids)tx.executeWithoutResult(ignored->{
            var app=get(id,true);
            if(clock.instant().isBefore(app.deadline()))return;
            if(app.status().equals("PENDING"))change(app,"EXPIRED","EXPIRED",null,null,"审批超时自动撤销");
            else if(app.status().equals("PROCESSING"))release(app,"EXPIRED",null,"处理超时，正在确认释放");
        });
    }
    public void reconcile() {
        for(int i=0;i<10;i++) {
            Boolean found=tx.execute(ignored->{
                var rows=db.query("select * from m2.applications where status in ('PROCESSING','RELEASING') and next_attempt_at<=? order by next_attempt_at,id limit 1 for update skip locked",this::row,ts(clock.instant()));
                if(rows.isEmpty())return false;
                var app=rows.getFirst();
                if(app.status().equals("PROCESSING")&&!clock.instant().isBefore(app.deadline())) {release(app,"EXPIRED",null,"处理超时，正在确认释放");return true;}
                try {
                    if(app.status().equals("PROCESSING")) {
                        var room=remote.resource(app.roomId());
                        try { validateResource(room,app.attendees(),app.startAt(),app.endAt()); }
                        catch(ApiException invalid) {release(app,"CONFLICT",null,"教室已停用或开放规则已变化，正在确认释放");return true;}
                    }
                    var receipt=remote.getOccupancy(app.id());
                    if(receipt==null||receipt.operationVersion()<app.operationVersion()) receipt=remote.putOccupancy(app.id(),new OccupancyWrite(app.roomId(),app.startAt(),app.endAt(),app.status().equals("PROCESSING")?"ACTIVE":"RELEASED",app.operationVersion(),app.id()+"-"+app.operationVersion()));
                    verifyReceipt(app,receipt);
                    if(app.status().equals("PROCESSING")) {
                        if(!clock.instant().isBefore(app.deadline())) {release(app,"EXPIRED",null,"占用结果确认晚于截止时间，正在释放");return true;}
                        if(receipt.state().equals("ACTIVE"))change(app,"APPROVED","OCCUPANCY_CONFIRMED",null,null,"教室占用已确认");
                        else if(receipt.state().equals("CONFLICT"))change(app,"CONFLICT","RESOURCE_CONFLICT",null,null,"该时段存在预约或维修限制，请换时段重新申请");
                        else throw ApiException.unavailable("INVALID_OCCUPANCY_STATE");
                    } else {
                        if(!receipt.state().equals("RELEASED"))throw ApiException.unavailable("RELEASE_NOT_CONFIRMED");
                        String target=db.queryForObject("select release_result from m2.applications where id=?",String.class,app.id());
                        change(app,target,"RELEASE_CONFIRMED",null,null,"已确认释放该申请的教室占用");
                    }
                    db.update("update m2.applications set last_error=null,needs_attention=false where id=?",app.id());
                } catch(ApiException ex) {
                    int attempts=app.attempts()+1;
                    db.update("update m2.applications set attempts=?,last_error=?,needs_attention=?,next_attempt_at=? where id=?",attempts,ex.code,attempts>=5,ts(clock.instant().plusSeconds(backoff(attempts))),app.id());
                }
                return true;
            });
            if(!Boolean.TRUE.equals(found))break;
        }
    }
    private void verifyReceipt(Application app,Occupancy receipt) {
        if(receipt==null||!app.id().equals(receipt.applicationId())||!app.roomId().equals(receipt.roomId())||!app.startAt().equals(receipt.startAt())||!app.endAt().equals(receipt.endAt())||app.operationVersion()!=receipt.operationVersion()||receipt.state()==null)throw ApiException.unavailable("INVALID_OCCUPANCY_RECEIPT");
    }
    public void dispatch() {
        for(int i=0;i<20;i++) {
            Boolean found=tx.execute(ignored->{
                var rows=db.queryForList("select * from m2.outbox where status='PENDING' and next_attempt_at<=? order by created_at,id limit 1 for update skip locked",ts(clock.instant()));
                if(rows.isEmpty())return false;var event=rows.getFirst();UUID id=(UUID)event.get("id");
                try {remote.event((String)event.get("kind"),event.get("payload").toString());db.update("update m2.outbox set status='SENT',last_error=null where id=?",id);}
                catch(ApiException ex) {
                    int attempts=((Number)event.get("attempts")).intValue()+1;
                    db.update("update m2.outbox set attempts=?,status=?,last_error=?,next_attempt_at=? where id=?",attempts,attempts>=8?"DEAD":"PENDING",ex.code,ts(clock.instant().plusSeconds(backoff(attempts))),id);
                }
                return true;
            });
            if(!Boolean.TRUE.equals(found))break;
        }
    }
    static long backoff(int attempts) {return Math.min(300,3L*(1L<<Math.min(attempts,7)));}
    private void release(Application app,String target,Identity actor,String comment) {
        db.update("update m2.applications set operation_version=operation_version+1,release_result=?,next_attempt_at=?,attempts=0,needs_attention=false where id=?",target,ts(clock.instant()),app.id());
        change(app,"RELEASING","RELEASE_REQUESTED",actor,null,comment);
    }
    private void change(Application app,String state,String action,Identity actor,Integer step,String comment) {
        db.update("update m2.applications set status=?,version=version+1,updated_at=? where id=?",state,ts(clock.instant()),app.id());
        record(get(app.id(),false),action,actor,step,comment);
    }
    private void record(Application app,String action,Identity actor,Integer step,String comment) {
        Instant now=clock.instant();UUID historyId=UUID.randomUUID();
        db.update("insert into m2.application_history(id,application_id,version,action,actor_id,actor_name,step,comment,occurred_at) values(?,?,?,?,?,?,?,?,?)",historyId,app.id(),app.version(),action,actor==null?null:actor.id(),actor==null?"系统自动处理":actor.displayName(),step,comment,ts(now));
        var audit=new LinkedHashMap<String,Object>();
        audit.put("eventId",historyId.toString());audit.put("actorId",actor==null?app.applicantId():actor.id());audit.put("departmentId",app.departmentId());
        audit.put("action","ROOM."+(actor==null?"SYSTEM_":"")+action);audit.put("objectType","ROOM_APPLICATION");audit.put("objectId",app.id().toString());audit.put("outcome","SUCCESS");audit.put("occurredAt",now);
        if(actor==null)audit.put("reasonCode","AUTOMATED_ON_BEHALF_OF_APPLICANT");
        enqueue(app.id(),"AUDIT",audit);
        enqueue(app.id(),"NOTIFICATION",Map.of("eventId",historyId.toString(),"recipientId",app.applicantId(),"title","教室申请进度更新","body",app.roomName()+"："+stateLabel(app.status())+"。请在教室申请页面查看详情。","objectType","ROOM_APPLICATION","objectId",app.id().toString()));
    }
    private void enqueue(UUID app,String kind,Object payload) {db.update("insert into m2.outbox(id,application_id,kind,payload,next_attempt_at,created_at) values(?,?,?,?::jsonb,?,?)",UUID.randomUUID(),app,kind,encode(payload),ts(clock.instant()),ts(clock.instant()));}
    static String stateLabel(String state) {return switch(state){case "PENDING"->"待审批";case "PROCESSING"->"占用处理中";case "APPROVED"->"已批准";case "REJECTED"->"已驳回";case "EXPIRED"->"已超时撤销";case "CANCELLED"->"已撤销";case "RELEASING"->"释放确认中";default->"资源冲突";};}
    private void validateResource(Resource room,int attendees,Instant start,Instant end) {
        if(room==null||!"ROOM".equals(room.type())||!room.effectiveActive()||room.departmentId()==null||room.version()<1)throw ApiException.bad("RESOURCE_UNAVAILABLE","教室不存在或已停用");
        if(attendees>room.capacity())throw ApiException.bad("CAPACITY","申请人数超过教室容量");
        var from=start.atZone(ZoneId.of("Asia/Shanghai"));var to=end.atZone(ZoneId.of("Asia/Shanghai"));
        if(!end.isAfter(start)||Duration.between(start,end).compareTo(Duration.ofHours(maxHours))>0||!from.toLocalDate().equals(to.toLocalDate())||from.getMinute()%30!=0||to.getMinute()%30!=0||from.getSecond()!=0||to.getSecond()!=0||from.getNano()!=0||to.getNano()!=0)throw ApiException.bad("TIME_RANGE","时间须为同一天的 30 分钟整数时段，且不超过单次时长上限");
        LocalTime cursor=from.toLocalTime();
        var windows=room.openingHours()==null?List.<OpeningHour>of():room.openingHours().stream().filter(h->h.day()==from.getDayOfWeek().getValue()).sorted(Comparator.comparing(OpeningHour::start)).toList();
        for(var window:windows)if(!window.start().isAfter(cursor)&&window.end().isAfter(cursor))cursor=window.end();
        if(cursor.isBefore(to.toLocalTime()))throw ApiException.bad("CLOSED_HOURS","申请时段不在教室开放时间内");
    }
    private void readable(Application app,Identity actor) {if(!app.applicantId().equals(actor.id())&&(!actor.scope(app.departmentId())||!(actor.can("rooms.review")||actor.can("rooms.approve")||actor.can("rooms.manage"))))throw ApiException.missing();}
    private void scope(Application app,Identity actor) {if(!actor.scope(app.departmentId()))throw ApiException.denied();}
    private void version(Application app,long expected) {if(app.version()!=expected)throw ApiException.conflict("申请已被其他操作更新，请刷新后重试");}
    private void require(Identity actor,String permission) {if(!actor.can(permission))throw ApiException.denied();}
    private Application get(UUID id,boolean lock) {var rows=db.query("select * from m2.applications where id=?"+(lock?" for update":""),this::row,id);if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();}
    private Application row(ResultSet r,int ignored) throws SQLException {
        List<Step> steps;try {steps=json.readValue(r.getString("steps"),new TypeReference<List<Step>>(){});}catch(Exception e){throw new IllegalStateException("Invalid workflow snapshot");}
        return new Application(r.getObject("id",UUID.class),r.getObject("applicant_id",UUID.class),r.getString("applicant_name"),r.getObject("department_id",UUID.class),r.getObject("room_id",UUID.class),r.getString("room_name"),r.getLong("resource_version"),r.getString("title"),r.getString("purpose"),r.getInt("attendees"),r.getTimestamp("start_at").toInstant(),r.getTimestamp("end_at").toInstant(),r.getTimestamp("deadline").toInstant(),steps,r.getInt("current_step"),r.getString("status"),r.getLong("version"),r.getLong("operation_version"),r.getInt("attempts"),r.getTimestamp("next_attempt_at").toInstant(),r.getString("last_error"),r.getBoolean("needs_attention"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Could not serialize application data");}}
    private String hash(Object value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static Timestamp ts(Instant instant) {return Timestamp.from(instant);}
}
