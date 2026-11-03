package uk.commuteglance

import android.app.*
import android.appwidget.*
import android.content.*
import android.os.SystemClock
import android.os.Bundle
import android.graphics.Color
import android.util.TypedValue
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import java.time.*
import java.util.concurrent.TimeUnit

class RefreshWorker(context:Context, params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        val repo=Repository(applicationContext)
        val mode=repo.settings.mode()
        val kinds=when(mode){"morning"->listOf("bus","out");"evening"->listOf("home");else->emptyList()}
        for(kind in kinds) try {repo.refresh(kind)} catch(e:Exception){/* Repository retains the error beside its original cache key. */}
        CommuteWidget.update(applicationContext)
        return Result.success()
    }
    companion object {
        fun schedule(c:Context){
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("commute-refresh",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RefreshWorker>(15,TimeUnit.MINUTES).build())
        }
        fun refresh(c:Context){WorkManager.getInstance(c).enqueueUniqueWork("refresh-now",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<RefreshWorker>().build())}
    }
}
class CommuteWidget:AppWidgetProvider() {
    override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){RefreshWorker.schedule(c);update(c);RefreshWorker.refresh(c)}
    override fun onAppWidgetOptionsChanged(c:Context,m:AppWidgetManager,id:Int,options:Bundle){update(c)}
    override fun onReceive(c:Context,i:Intent){super.onReceive(c,i);if(i.action=="uk.commuteglance.REFRESH")RefreshWorker.refresh(c)}
    companion object {
        fun open(c:Context):PendingIntent=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun update(c:Context){
            val repo=Repository(c);val s=repo.settings;val mode=s.mode()
            val kinds=when(mode){"morning"->listOf("bus","out");"evening"->listOf("home");else->emptyList()}
            val title=when(mode){"morning"->"To work · ${s.stopName}";"evening"->"London Bridge → East Croydon";else->"Commute Glance · outside commute hours"}
            val rows=when(mode){"morning"->"BUS\n${repo.summary("bus",2,false)}\n\nECR → LBG\n${repo.summary("out",2,false)}";"evening"->repo.summary("home",3,false);else->"Tap to view departures anytime\nScheduled windows in Settings"}
            val manager=AppWidgetManager.getInstance(c)
            for(id in manager.getAppWidgetIds(ComponentName(c,CommuteWidget::class.java))) {
                val options=manager.getAppWidgetOptions(id)
                val compact=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,300)<280 || c.resources.configuration.fontScale>1.3f
                manager.updateAppWidget(id,buildViews(c,repo,mode,kinds,compact))
            }
            notification(c,repo,mode,title,rows)
        }
        private val muted=Color.rgb(148,155,171)
        private val amber=Color.rgb(229,185,111)

        private fun buildViews(c:Context,repo:Repository,mode:String,kinds:List<String>,compact:Boolean):RemoteViews {
            val views=RemoteViews(c.packageName,R.layout.widget)
            val s=repo.settings
            val active=mode!="off"
            views.setOnClickPendingIntent(R.id.widget_root,open(c))
            views.setOnClickPendingIntent(R.id.refresh,if(active) PendingIntent.getBroadcast(c,1,
                Intent(c,CommuteWidget::class.java).setAction("uk.commuteglance.REFRESH"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else open(c))
            views.setTextViewText(R.id.refresh,if(active) "↻" else "↗")
            views.setContentDescription(R.id.refresh,if(active) "Refresh departures" else "Open departures")
            views.setViewVisibility(R.id.active_board,if(active) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.off_board,if(active) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.age,View.GONE)
            if(!active) {
                val next=CommuteRules.nextWindow(ZonedDateTime.now(),s.get("days","1,4").split(',').mapNotNull {it.trim().toIntOrNull()}.toSet(),
                    LocalTime.parse(s.get("morningStart","07:00")),LocalTime.parse(s.get("eveningStart","16:00")))
                views.setTextViewText(R.id.next_window,next?.first?.format(DateTimeFormatter.ofPattern("EEE, HH:mm",Locale.UK)) ?: "Schedule")
                views.setTextViewText(R.id.next_direction,if(next?.second=="evening") "London Bridge → East Croydon" else "East Croydon → London Bridge")
                views.setTextViewText(R.id.off_detail,"${s.stopName}\n${s.routes.replace(","," / ")} · ${s.walk} min walk")
                views.setTextViewText(R.id.footer,"Open departures ↗")
                return views
            }
            val now=Instant.now()
            val kind=if(mode=="morning") "bus" else "home"
            val board=repo.board(kind)
            val expired=board!=null && Duration.between(board.fetched,now).seconds>600
            val stale=board!=null && (board.stale || board.warning.isNotBlank() || repo.error(kind).isNotBlank() ||
                Duration.between(board.fetched,now).seconds>90 || board.sourceAt?.let {Duration.between(it,now).seconds>120}==true)
            views.setTextViewText(R.id.title,if(mode=="morning") "BUS ESTIMATE · ${s.walk} MIN WALK" else "FAST TRAINS · TO EAST CROYDON")
            views.setTextViewText(R.id.freshness,when {expired->"EXPIRED";stale->"STALE";board==null->"NO DATA";else->""})
            views.setTextColor(R.id.freshness,if(expired||stale) amber else muted)
            val departures=board?.takeUnless {expired}?.departures?.filter {
                val at=if(it.status=="Cancelled") it.scheduled else it.expected?:it.scheduled
                at==null || at.isAfter(now.minusSeconds(60))
            }.orEmpty()
            val next=departures.firstOrNull {it.status!="Cancelled" && it.expected?.isAfter(now.plusSeconds(if(mode=="morning") s.walk*60L else 0))==true}
            views.setViewVisibility(R.id.hero,if(next!=null) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.hero_empty,if(next==null) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.countdown_note,if(next!=null) View.VISIBLE else View.GONE)
            views.setTextViewTextSize(R.id.remaining,TypedValue.COMPLEX_UNIT_SP,if(compact) 34f else 40f)
            if(next!=null) {
                views.setChronometer(R.id.remaining,SystemClock.elapsedRealtime()+Duration.between(now,next.expected!!).toMillis(),"~ %s",true)
                views.setChronometerCountDown(R.id.remaining,true)
                views.setTextViewText(R.id.hero_route,next.route)
                views.setTextViewText(R.id.destination,next.destination)
                views.setTextViewText(R.id.expected,"${CommuteRules.time(next.expected)} expected · ${board!!.source} checked ${CommuteRules.time(board.fetched)}")
            } else {
                views.setTextViewText(R.id.hero_empty,if(expired) "Refresh needed" else if(board==null) "Not connected" else "No catchable service")
                views.setTextViewText(R.id.destination,if(mode=="morning") s.stopName else "London Bridge → East Croydon")
                views.setTextViewText(R.id.expected,if(expired) "Saved predictions are over 10 minutes old" else if(board==null) "Tap refresh to fetch departures" else "Tap to see all departures")
            }
            val rowDepartures=departures.filter {it!=next}
            val rowIds=listOf(
                intArrayOf(R.id.row_one,R.id.route_one,R.id.destination_one,R.id.state_one,R.id.time_one),
                intArrayOf(R.id.row_two,R.id.route_two,R.id.destination_two,R.id.state_two,R.id.time_two))
            for((index,ids) in rowIds.withIndex()) {
                val d=rowDepartures.getOrNull(index)
                val visible=d!=null && !(compact && index==1)
                views.setViewVisibility(ids[0],if(visible) View.VISIBLE else View.GONE)
                if(!visible) continue
                d!!
                val at=if(d.status=="Cancelled") d.scheduled else d.expected?:d.scheduled
                val remaining=d.expected?.let {CommuteRules.minutesRemaining(it,now)}
                val state=when {
                    d.status=="Cancelled"->"CANCELLED"
                    remaining==null->"Scheduled · no prediction"
                    remaining<=0->"Prediction elapsed · refresh"
                    d.duration==0 && d.expected!!.isBefore(now.plusSeconds(s.walk*60L))->"Too soon for ${s.walk} min walk"
                    else->"Estimated"
                }
                val delay=if(d.duration>0 && d.expected!=null && d.scheduled!=null && d.status!="Cancelled") Duration.between(d.scheduled,d.expected).toMinutes() else 0
                val details=if(d.duration>0 && d.status!="Cancelled") " · ${d.duration}m · P${d.platform.ifBlank {"—"}}" else ""
                views.setTextViewText(ids[1],d.route)
                views.setTextViewText(ids[2],d.destination)
                views.setTextViewText(ids[3],state+details+if(delay>0) " · +${delay}m late" else "")
                views.setTextColor(ids[3],if(d.status=="Cancelled" || remaining!=null && remaining<=0 || delay>0) amber else muted)
                views.setTextViewText(ids[4],at?.let {CommuteRules.time(it)} ?: "—")
                views.setContentDescription(ids[0],d.line(now,s.walk,false))
            }
            views.setViewVisibility(R.id.rail,if(mode=="morning") View.VISIBLE else View.GONE)
            if(mode=="morning") {
                val rail=repo.board("out")
                val railExpired=rail!=null && Duration.between(rail.fetched,now).seconds>600
                val railNext=rail?.takeUnless {railExpired}?.departures?.firstOrNull {(it.expected?:it.scheduled)?.isAfter(now)!=false}
                val state=when {
                    railExpired->"Data expired · refresh"
                    railNext!=null->railNext.railWidgetLine()
                    repo.error("out").startsWith("Rail not configured")->"Rail not connected"
                    repo.error("out").isNotBlank()->"Rail unavailable"
                    rail==null->"No rail data yet"
                    else->"No upcoming fast trains"
                }
                views.setTextViewText(R.id.rail,"ECR → LBG · $state")
            }
            val oldest=kinds.mapNotNull {repo.board(it)?.fetched}.minOrNull()
            if(oldest!=null) {
                views.setViewVisibility(R.id.age,View.VISIBLE)
                views.setChronometer(R.id.age,SystemClock.elapsedRealtime()-Duration.between(oldest,now).toMillis().coerceAtLeast(0),"Data age  %s",true)
                views.setTextColor(R.id.age,if(stale||expired) amber else muted)
            }
            views.setTextViewText(R.id.footer,if(oldest!=null) "Stale after 1:30 · expired after 10:00" else "No cached predictions · tap to open")
            return views
        }
        private fun notification(c:Context,repo:Repository,mode:String,title:String,rows:String){
            val nm=c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("commute","Commute departures",NotificationManager.IMPORTANCE_LOW).apply {description="Silent departure board during your commute windows";setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PUBLIC})
            if(mode=="off"||!repo.settings.notifications){nm.cancel(42);return}
            val now=ZonedDateTime.now(CommuteRules.zone)
            val end=LocalTime.parse(repo.settings.get(if(mode=="morning") "morningEnd" else "eveningEnd",if(mode=="morning") "08:30" else "19:00"))
            val timeout=Duration.between(now,now.with(end)).toMillis().coerceAtLeast(1000)
            val n=Notification.Builder(c,"commute").setSmallIcon(R.drawable.ic_bus).setContentTitle(title)
                .setContentText("Open departures").setStyle(Notification.BigTextStyle().bigText(rows))
                .setContentIntent(open(c)).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
                .setTimeoutAfter(timeout).setShowWhen(true).setWhen(repo.board(if(mode=="morning") "bus" else "home")?.fetched?.toEpochMilli() ?: System.currentTimeMillis()).build()
            try {nm.notify(42,n)}catch(_:SecurityException){ /* Permission declined; widget still works. */ }
        }
    }
}
