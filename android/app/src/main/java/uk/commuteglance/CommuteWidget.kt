package uk.commuteglance

import android.app.*
import android.appwidget.*
import android.content.*
import android.os.SystemClock
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
    override fun onReceive(c:Context,i:Intent){super.onReceive(c,i);if(i.action=="uk.commuteglance.REFRESH")RefreshWorker.refresh(c)}
    companion object {
        fun open(c:Context):PendingIntent=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun update(c:Context){
            val repo=Repository(c);val s=repo.settings;val mode=s.mode()
            val kinds=when(mode){"morning"->listOf("bus","out");"evening"->listOf("home");else->emptyList()}
            val title=when(mode){"morning"->"To work · ${s.stopName}";"evening"->"London Bridge → East Croydon";else->"Commute Glance · outside commute hours"}
            val rows=when(mode){"morning"->"BUS\n${repo.summary("bus",2,false)}\n\nECR → LBG\n${repo.summary("out",2,false)}";"evening"->repo.summary("home",3,false);else->"Tap to view departures anytime\nScheduled windows in Settings"}
            val widgetRows=when(mode){"morning"->"${repo.summary("bus",2,false,true)}\nECR → LBG\n${repo.summary("out",1,false,true)}";"evening"->repo.summary("home",3,false,true);else->rows}
            val views=RemoteViews(c.packageName,R.layout.widget)
            views.setTextViewText(R.id.title,title);views.setTextViewText(R.id.rows,widgetRows)
            views.setOnClickPendingIntent(R.id.rows,open(c));views.setOnClickPendingIntent(R.id.title,open(c))
            views.setOnClickPendingIntent(R.id.refresh,PendingIntent.getBroadcast(c,1,Intent(c,CommuteWidget::class.java).setAction("uk.commuteglance.REFRESH"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val nowInstant = Instant.now()
            val primary = repo.board(if(mode=="morning") "bus" else "home")
            val next = primary?.takeIf { Duration.between(it.fetched,nowInstant).seconds <= 600 }?.departures?.firstOrNull {
                it.status != "Cancelled" && it.expected?.isAfter(nowInstant.plusSeconds(if(mode=="morning") s.walk*60L else 0L)) == true
            }
            val showCountdown = mode!="off" && next!=null
            views.setViewVisibility(R.id.remaining,if(showCountdown) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.countdown_note,if(showCountdown) View.VISIBLE else View.GONE)
            if(showCountdown) {
                val delta=Duration.between(nowInstant,next!!.expected!!).toMillis()
                views.setChronometer(R.id.remaining,SystemClock.elapsedRealtime()+delta,"~ %s · "+next.route.replace("%", ""),true)
                views.setChronometerCountDown(R.id.remaining,true)
            }
            val oldest=kinds.mapNotNull {repo.board(it)?.fetched}.minOrNull()
            views.setViewVisibility(R.id.age,if(oldest==null) View.GONE else View.VISIBLE)
            if(oldest!=null) {
                val age=Duration.between(oldest,Instant.now()).toMillis().coerceAtLeast(0)
                views.setChronometer(R.id.age,SystemClock.elapsedRealtime()-age,"Oldest fetch: %s ago · >1:30 = stale",true)
            }
            val manager=AppWidgetManager.getInstance(c)
            manager.updateAppWidget(manager.getAppWidgetIds(ComponentName(c,CommuteWidget::class.java)),views)
            notification(c,repo,mode,title,rows)
        }
        private fun notification(c:Context,repo:Repository,mode:String,title:String,rows:String){
            val nm=c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("commute","Commute departures",NotificationManager.IMPORTANCE_LOW).apply {description="Silent departure board during your commute windows";setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PUBLIC})
            if(mode=="off"||!repo.settings.notifications){nm.cancel(42);return}
            val now=ZonedDateTime.now(CommuteRules.zone)
            val end=LocalTime.parse(repo.settings.get(if(mode=="morning") "morningEnd" else "eveningEnd",if(mode=="morning") "08:30" else "19:00"))
            val timeout=Duration.between(now,now.with(end)).toMillis().coerceAtLeast(1000)
            val n=Notification.Builder(c,"commute").setSmallIcon(R.drawable.ic_bus).setContentTitle(title)
                .setContentText("Cached estimates · tap to refresh").setStyle(Notification.BigTextStyle().bigText(rows))
                .setContentIntent(open(c)).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
                .setTimeoutAfter(timeout).setShowWhen(true).setWhen(repo.board(if(mode=="morning") "bus" else "home")?.fetched?.toEpochMilli() ?: System.currentTimeMillis()).build()
            try {nm.notify(42,n)}catch(_:SecurityException){ /* Permission declined; widget still works. */ }
        }
    }
}
