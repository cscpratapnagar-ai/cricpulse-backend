package com.cricket.platform.scoring;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Component
public class RecordDelivery {
    private static final Set<String> EXTRAS = Set.of("WIDE", "NO_BALL", "BYE", "LEG_BYE", "PENALTY");
    private static final Set<String> WICKETS = Set.of("BOWLED", "CAUGHT", "LBW", "RUN_OUT", "STUMPED", "HIT_WICKET", "RETIRED_HURT");
    private static final Set<String> BOWLER_WICKETS = Set.of("BOWLED", "CAUGHT", "LBW", "STUMPED", "HIT_WICKET");
    private final JdbcTemplate jdbc;

    public RecordDelivery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public DeliveryResponse execute(Request request) {
        validate(request);
        InningsState innings = jdbc.queryForObject("""
                SELECT id,total_runs,wickets,legal_balls,current_over,current_ball,
                       striker_id,non_striker_id,current_bowler_id,status
                FROM innings WHERE id=? FOR UPDATE
                """, (rs,row)->new InningsState(
                rs.getObject("id",UUID.class),rs.getInt("total_runs"),rs.getInt("wickets"),rs.getInt("legal_balls"),
                rs.getInt("current_over"),rs.getInt("current_ball"),rs.getObject("striker_id",UUID.class),
                rs.getObject("non_striker_id",UUID.class),rs.getObject("current_bowler_id",UUID.class),rs.getString("status")),request.inningsId());
        if (innings == null) throw new IllegalArgumentException("Innings was not found");
        if (!"LIVE".equals(innings.status())) throw new IllegalArgumentException("Innings is not live");

        UUID dismissed = request.wicketType()==null ? null : (request.dismissedPlayerId()!=null ? request.dismissedPlayerId() : request.strikerId());
        Request normalized = new Request(request.inningsId(),request.overNumber(),request.ballNumber(),request.strikerId(),request.nonStrikerId(),request.bowlerId(),request.batRuns(),request.extraRuns(),request.extraType(),request.wicketType(),dismissed,request.newBatterId());
        validateState(normalized,innings);

        boolean legal = !"WIDE".equals(normalized.extraType()) && !"NO_BALL".equals(normalized.extraType());
        int totalRuns = normalized.batRuns()+normalized.extraRuns();
        int oldLegal = innings.legalBalls();
        int newLegal = oldLegal+(legal?1:0);
        int newWickets = innings.wickets()+(normalized.wicketType()==null?0:1);
        // Delivery belongs to the over that was in progress before this ball.
        int overNumber = oldLegal/6;
        int ballNumber = legal ? (oldLegal%6)+1 : Math.max(1,oldLegal%6==0?1:((oldLegal-1)%6)+1);

        UUID deliveryId=UUID.randomUUID();
        Integer sequence=jdbc.queryForObject("SELECT COALESCE(MAX(sequence_number),0)+1 FROM deliveries WHERE innings_id=?",Integer.class,normalized.inningsId());
        jdbc.update("""
                INSERT INTO deliveries(id,innings_id,over_number,ball_number,striker_id,non_striker_id,bowler_id,
                    bat_runs,extra_runs,extra_type,wicket_type,dismissed_player_id,sequence_number,legal_delivery,
                    total_runs,is_boundary,is_four,is_six)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,deliveryId,normalized.inningsId(),overNumber,ballNumber,normalized.strikerId(),normalized.nonStrikerId(),normalized.bowlerId(),
                normalized.batRuns(),normalized.extraRuns(),normalized.extraType(),normalized.wicketType(),normalized.dismissedPlayerId(),sequence,legal,totalRuns,
                normalized.batRuns()==4,normalized.batRuns()==4,normalized.batRuns()==6);

        jdbc.update("""
                UPDATE innings SET total_runs=?,wickets=?,legal_balls=?,current_over=?,current_ball=?,current_bowler_id=? WHERE id=?
                """,innings.totalRuns()+totalRuns,newWickets,newLegal,newLegal/6,newLegal%6,normalized.bowlerId(),normalized.inningsId());

        updateOver(normalized,overNumber,legal,totalRuns);
        updateBatter(normalized,deliveryId,legal);
        updateBowler(normalized,legal,totalRuns);
        updatePartnership(normalized,legal,totalRuns);

        UUID nextStriker=normalized.strikerId(), nextNonStriker=normalized.nonStrikerId();
        if(totalRuns%2!=0){UUID t=nextStriker;nextStriker=nextNonStriker;nextNonStriker=t;}

        if(normalized.wicketType()!=null){
            recordFow(normalized,deliveryId,innings.totalRuns()+totalRuns,overNumber,ballNumber);
            if(newWickets>=10){
                if(normalized.dismissedPlayerId().equals(nextStriker))nextStriker=null;
                if(normalized.dismissedPlayerId().equals(nextNonStriker))nextNonStriker=null;
            }else{
                if(normalized.dismissedPlayerId().equals(nextStriker))nextStriker=normalized.newBatterId();
                if(normalized.dismissedPlayerId().equals(nextNonStriker))nextNonStriker=normalized.newBatterId();
                jdbc.update("INSERT INTO partnerships(innings_id,wicket_number,batter_one_id,batter_two_id,runs,balls,is_current) VALUES(?,?,?, ?,0,0,TRUE)",normalized.inningsId(),newWickets,nextStriker,nextNonStriker);
            }
        }

        // End-of-over strike rotation happens after wicket replacement so the persisted
        // striker/non-striker pair represents the next ball correctly.
        if(legal && newLegal%6==0 && newWickets<10){UUID t=nextStriker;nextStriker=nextNonStriker;nextNonStriker=t;}
        jdbc.update("UPDATE innings SET striker_id=?,non_striker_id=? WHERE id=?",nextStriker,nextNonStriker,normalized.inningsId());
        return new DeliveryResponse(deliveryId,innings.totalRuns()+totalRuns,newWickets,newLegal,overNumber,ballNumber,nextStriker,nextNonStriker);
    }

    private void validateState(Request r,InningsState s){
        if(s.strikerId()==null||s.nonStrikerId()==null)throw new IllegalArgumentException("Current striker and non-striker must be set");
        if(!s.strikerId().equals(r.strikerId())||!s.nonStrikerId().equals(r.nonStrikerId()))throw new IllegalArgumentException("Striker/non-striker does not match the current innings state");
        boolean boundary=s.legalBalls()>0&&s.legalBalls()%6==0;
        if(s.currentBowlerId()!=null){
            if(boundary&&s.currentBowlerId().equals(r.bowlerId()))throw new IllegalArgumentException("A new bowler must be selected for the next over");
            if(!boundary&&!s.currentBowlerId().equals(r.bowlerId()))throw new IllegalArgumentException("Bowler cannot change before the over is completed");
        }
        if(r.wicketType()!=null){
            if(!r.dismissedPlayerId().equals(r.strikerId())&&!r.dismissedPlayerId().equals(r.nonStrikerId()))throw new IllegalArgumentException("Dismissed player must be the current striker or non-striker");
            int next=s.wickets()+1;
            if(next<10&&r.newBatterId()==null)throw new IllegalArgumentException("New batter is required after a wicket");
            if(next>=10&&r.newBatterId()!=null)throw new IllegalArgumentException("No new batter is allowed after the 10th wicket");
            if(r.newBatterId()!=null&&(r.newBatterId().equals(r.strikerId())||r.newBatterId().equals(r.nonStrikerId())||r.newBatterId().equals(r.dismissedPlayerId())))throw new IllegalArgumentException("New batter must be a different available player");
        }else if(r.newBatterId()!=null||r.dismissedPlayerId()!=null)throw new IllegalArgumentException("Wicket fields can only be supplied for a wicket delivery");
    }

    private void updateOver(Request r,int over,boolean legal,int runs){jdbc.update("""
            INSERT INTO innings_overs(innings_id,over_number,bowler_id,runs,wickets,legal_balls,wides,no_balls,byes,leg_byes)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(innings_id,over_number) DO UPDATE SET
            runs=innings_overs.runs+EXCLUDED.runs,wickets=innings_overs.wickets+EXCLUDED.wickets,
            legal_balls=innings_overs.legal_balls+EXCLUDED.legal_balls,wides=innings_overs.wides+EXCLUDED.wides,
            no_balls=innings_overs.no_balls+EXCLUDED.no_balls,byes=innings_overs.byes+EXCLUDED.byes,
            leg_byes=innings_overs.leg_byes+EXCLUDED.leg_byes,
            completed=innings_overs.legal_balls+EXCLUDED.legal_balls>=6
            """,r.inningsId(),over,r.bowlerId(),runs,r.wicketType()==null?0:1,legal?1:0,
            "WIDE".equals(r.extraType())?r.extraRuns():0,"NO_BALL".equals(r.extraType())?r.extraRuns():0,
            "BYE".equals(r.extraType())?r.extraRuns():0,"LEG_BYE".equals(r.extraType())?r.extraRuns():0);}

    private void updateBatter(Request r,UUID delivery,boolean legal){jdbc.update("""
            INSERT INTO innings_batters(innings_id,player_id,runs,balls_faced,fours,sixes,is_out, dismissal_type,dismissal_delivery_id,strike_rate)
            VALUES(?,?,?,?,?,?,?,?,?,0) ON CONFLICT(innings_id,player_id) DO UPDATE SET
            runs=innings_batters.runs+EXCLUDED.runs,balls_faced=innings_batters.balls_faced+EXCLUDED.balls_faced,
            fours=innings_batters.fours+EXCLUDED.fours,sixes=innings_batters.sixes+EXCLUDED.sixes,
            is_out=innings_batters.is_out OR EXCLUDED.is_out,dismissal_type=COALESCE(EXCLUDED.dismissal_type,innings_batters.dismissal_type),
            dismissal_delivery_id=COALESCE(EXCLUDED.dismissal_delivery_id,innings_batters.dismissal_delivery_id),
            strike_rate=CASE WHEN innings_batters.balls_faced+EXCLUDED.balls_faced=0 THEN 0 ELSE ROUND(((innings_batters.runs+EXCLUDED.runs)::numeric*100)/(innings_batters.balls_faced+EXCLUDED.balls_faced),2) END
            """,r.inningsId(),r.strikerId(),r.batRuns(),legal?1:0,r.batRuns()==4?1:0,r.batRuns()==6?1:0,
            r.wicketType()!=null&&r.dismissedPlayerId().equals(r.strikerId()),r.wicketType(),r.wicketType()==null?null:delivery);}

    private void updateBowler(Request r,boolean legal,int total){int conceded=switch(r.extraType()==null?"":r.extraType()){case "BYE","LEG_BYE"->0;default->total;};jdbc.update("""
            INSERT INTO innings_bowlers(innings_id,player_id,legal_balls,runs_conceded,wickets,wides,no_balls,economy)
            VALUES(?,?,?,?,?,?,?,0) ON CONFLICT(innings_id,player_id) DO UPDATE SET
            legal_balls=innings_bowlers.legal_balls+EXCLUDED.legal_balls,runs_conceded=innings_bowlers.runs_conceded+EXCLUDED.runs_conceded,
            wickets=innings_bowlers.wickets+EXCLUDED.wickets,wides=innings_bowlers.wides+EXCLUDED.wides,no_balls=innings_bowlers.no_balls+EXCLUDED.no_balls,
            economy=CASE WHEN innings_bowlers.legal_balls+EXCLUDED.legal_balls=0 THEN 0 ELSE ROUND(((innings_bowlers.runs_conceded+EXCLUDED.runs_conceded)::numeric*6)/(innings_bowlers.legal_balls+EXCLUDED.legal_balls),2) END
            """,r.inningsId(),r.bowlerId(),legal?1:0,conceded,isBowlerWicket(r.wicketType())?1:0,"WIDE".equals(r.extraType())?r.extraRuns():0,"NO_BALL".equals(r.extraType())?r.extraRuns():0);}

    private void updatePartnership(Request r,boolean legal,int total){jdbc.update("UPDATE partnerships SET runs=runs+?,balls=balls+? WHERE innings_id=? AND is_current=TRUE",total,legal?1:0,r.inningsId());}
    private void recordFow(Request r,UUID delivery,int score,int over,int ball){Integer n=jdbc.queryForObject("SELECT COALESCE(MAX(wicket_number),0)+1 FROM fall_of_wickets WHERE innings_id=?",Integer.class,r.inningsId());jdbc.update("INSERT INTO fall_of_wickets(innings_id,wicket_number,player_id,runs,over_number,ball_number,delivery_id) VALUES(?,?,?,?,?,?,?)",r.inningsId(),n,r.dismissedPlayerId(),score,over,ball,delivery);jdbc.update("UPDATE partnerships SET is_current=FALSE WHERE innings_id=? AND is_current=TRUE",r.inningsId());}
    private boolean isBowlerWicket(String t){return t!=null&&BOWLER_WICKETS.contains(t);}
    private void validate(Request r){if(r==null)throw new IllegalArgumentException("Delivery request is required");if(r.batRuns()<0||r.batRuns()>6)throw new IllegalArgumentException("Bat runs must be between 0 and 6");if(r.extraRuns()<0)throw new IllegalArgumentException("Extra runs cannot be negative");if(r.extraType()!=null&&!EXTRAS.contains(r.extraType()))throw new IllegalArgumentException("Unsupported extra type");if(r.wicketType()!=null&&!WICKETS.contains(r.wicketType()))throw new IllegalArgumentException("Unsupported wicket type");if(r.extraRuns()>0&&r.extraType()==null)throw new IllegalArgumentException("Extra type is required when extra runs are recorded");if("WIDE".equals(r.extraType())&&r.batRuns()!=0)throw new IllegalArgumentException("Wide cannot contain bat runs");if("WIDE".equals(r.extraType())&&r.extraRuns()<1)throw new IllegalArgumentException("Wide must contain at least one extra run");if("NO_BALL".equals(r.extraType())&&r.extraRuns()<1)throw new IllegalArgumentException("No-ball must contain at least one extra run");if(r.overNumber()<0||r.ballNumber()<1)throw new IllegalArgumentException("Invalid over/ball number");}

    private record InningsState(UUID id,int totalRuns,int wickets,int legalBalls,int currentOver,int currentBall,UUID strikerId,UUID nonStrikerId,UUID currentBowlerId,String status){}
    public record Request(@NotNull UUID inningsId,@Min(0) int overNumber,@Min(1) int ballNumber,@NotNull UUID strikerId,@NotNull UUID nonStrikerId,@NotNull UUID bowlerId,@Min(0) int batRuns,@Min(0) int extraRuns,String extraType,String wicketType,UUID dismissedPlayerId,UUID newBatterId){}
    public record DeliveryResponse(UUID deliveryId,int totalRuns,int wickets,int legalBalls,int overNumber,int ballNumber,UUID strikerId,UUID nonStrikerId){}
}
