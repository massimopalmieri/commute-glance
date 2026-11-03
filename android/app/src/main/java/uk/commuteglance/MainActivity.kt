package uk.commuteglance

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.WindowInsets
import java.time.Instant
import java.time.Duration
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONArray
import java.net.URI
import java.time.LocalTime
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var boardContent: LinearLayout
    private var renderKey = ""
    private var mode = "morning"
    private var busy = false
    private var settingsOpen = false
    private val tick = object : Runnable { override fun run() { if (!settingsOpen) render(); handler.postDelayed(this, 1000) } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(Build.VERSION.SDK_INT>=30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.rgb(12,13,16); window.navigationBarColor = Color.rgb(12,13,16)
        mode = Settings(this).mode().let { if(it=="off") "morning" else it }
        RefreshWorker.schedule(this); home(); refresh()
        if (Settings(this).base.isBlank()) settings()
    }
    override fun onResume() { super.onResume(); handler.post(tick); if(!settingsOpen) refresh() }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
    private val ink = Color.rgb(238,240,245)
    private val muted = Color.rgb(143,149,163)
    private val accent = Color.rgb(157,183,255)
    private val amber = Color.rgb(229,185,111)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(fill: Int, border: Int = Color.rgb(43,46,55), radius: Float = 16f) = GradientDrawable().apply {
        setColor(fill); cornerRadius=dp(radius.toInt()).toFloat(); setStroke(dp(1),border)
    }
    private fun column(parent: LinearLayout, gap: Int = 0): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(gap) })
    }
    private fun row(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        parent.addView(this,LinearLayout.LayoutParams(-1,-2))
    }
    private fun label(parent: LinearLayout, value: String, size: Float=14f, color: Int=ink, bold: Boolean=false): TextView = TextView(this).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false
        typeface=Typeface.create(if(bold) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        parent.addView(this,LinearLayout.LayoutParams(-2,-2))
    }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun divider(parent: LinearLayout) { parent.addView(View(this).apply {setBackgroundColor(Color.rgb(39,42,50))},LinearLayout.LayoutParams(-1,dp(1)).apply {topMargin=dp(14);bottomMargin=dp(14)}) }
    private fun card(parent: LinearLayout): LinearLayout = column(parent,16).apply {
        background=shape(Color.rgb(20,22,27));setPadding(dp(18),dp(18),dp(18),dp(18))
    }
    private fun action(parent: LinearLayout, title: String, primary: Boolean=false, run: () -> Unit): TextView = TextView(this).apply {
        text=title;textSize=14f;setTextColor(if(primary) Color.rgb(15,23,43) else ink)
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);gravity=Gravity.CENTER;minHeight=dp(48)
        background=RippleDrawable(ColorStateList.valueOf(0x224D76CC),shape(if(primary) accent else Color.rgb(26,29,36),radius=12f),null)
        setPadding(dp(14),dp(12),dp(14),dp(12));isClickable=true;isFocusable=true;setOnClickListener {run()}
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
    }
    private fun root() {
        val scroll=ScrollView(this).apply {isFillViewport=true;setBackgroundColor(Color.rgb(12,13,16));clipToPadding=false}
        content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(24),dp(22),dp(28))}
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, i ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            } else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom)
            i
        }
        setContentView(scroll)
    }
    private fun text(value: String, size: Float=16f): TextView = label(content,value,size).apply {setPadding(0,dp(10),0,dp(8))}
    private fun button(label: String, action: () -> Unit) { action(content,label,run=action) }
    private fun home() {
        settingsOpen=false;root();renderKey=""
        val header=row(content)
        header.addView(ImageView(this).apply {setImageResource(R.drawable.ic_launcher_foreground);background=shape(Color.rgb(28,35,54),radius=10f);contentDescription="Commute Glance"},LinearLayout.LayoutParams(dp(36),dp(36)))
        label(header,"  commute / glance",16f,ink,true).apply {layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        label(header,"↻",26f,accent).apply {
            gravity=Gravity.CENTER;minWidth=dp(44);minHeight=dp(48);contentDescription="Refresh departures"
            isClickable=true;isFocusable=true;setOnClickListener {refresh()}
        }
        label(header,"⋯",26f,muted).apply {
            gravity=Gravity.CENTER;minWidth=dp(44);minHeight=dp(48);contentDescription="Settings"
            isClickable=true;isFocusable=true;setOnClickListener {settings()}
        }
        val tabs=row(column(content,24)).apply {background=shape(Color.rgb(20,22,27),radius=12f);setPadding(dp(4),dp(4),dp(4),dp(4))}
        for ((key,title) in listOf("morning" to "To work  ↗","evening" to "Going home  ↙")) {
            label(tabs,title,14f,if(mode==key) ink else muted,true).apply {
                gravity=Gravity.CENTER;minHeight=dp(42);layoutParams=LinearLayout.LayoutParams(0,-2,1f)
                if(mode==key) background=shape(Color.rgb(42,46,57),radius=9f)
                isClickable=true;isFocusable=true;setOnClickListener {if(mode!=key){mode=key;home();refresh()}}
            }
        }
        boardContent=column(content)
        val actions=row(column(content,14))
        action(actions,"↻  Refresh",true) {refresh()}.apply {layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(8)}}
        action(actions,"Settings") {settings()}.apply {layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        action(content,"＋  Add home-screen widget") {
            val manager=getSystemService(AppWidgetManager::class.java)
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this,CommuteWidget::class.java),null,null)
            else toast("Long-press your home screen and choose Widgets")
        }
        space(content,20)
        label(content,"MON / THU     07:00–08:30 · 16:00–19:00",10f,muted,true).apply {text="${Settings(this@MainActivity).get("days","1,4").split(',').mapNotNull {it.trim().toIntOrNull()}.joinToString(" / ") {listOf("MON","TUE","WED","THU","FRI","SAT","SUN").getOrElse(it-1){"?"}}}   ·   Europe/London"}
        render()
    }
    private fun render() {
        if(!::boardContent.isInitialized || settingsOpen) return
        val repo=Repository(this);val kind=if(mode=="morning") "bus" else "home"
        val signature="$mode|$busy|${repo.summary(kind,4,true)}|${repo.summary("out",2,true)}"
        if(signature==renderKey) return
        renderKey=signature;boardContent.removeAllViews()
        val b=repo.board(kind);val now=Instant.now()
        val age=b?.let {Duration.between(it.fetched,now).seconds.coerceAtLeast(0)}
        val stale=b!=null && (b.stale || b.warning.isNotBlank() || age!!>90 || repo.error(kind).isNotBlank() || b.sourceAt?.let {Duration.between(it,now).seconds>120}==true)
        val expired=age!=null && age>600
        val top=row(column(boardContent,20))
        label(top,if(mode=="morning") "BUS DEPARTURES" else "RAIL DEPARTURES",10f,muted,true).apply {letterSpacing=.12f;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        label(top,if(busy) "↻  Refreshing" else if(expired) "●  Expired" else if(stale) "●  Stale" else if(b!=null) "●  ${b.source}" else "○  Unavailable",11f,if(stale||expired) amber else accent)
        if(mode=="morning") {
            val next=b?.takeIf {!expired}?.departures?.firstOrNull {it.status!="Cancelled" && it.expected?.isAfter(now.plusSeconds(repo.settings.walk*60L))==true}
            val hero=card(boardContent)
            label(hero,"NEXT CATCHABLE BUS",10f,muted,true).apply {letterSpacing=.1f}
            space(hero,12)
            if(next!=null) {
                val line=row(hero)
                label(line,"${CommuteRules.minutesRemaining(next.expected!!,now)}",60f,ink).apply {typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)}
                label(line,"  min",17f,muted)
                label(line,"  ${next.route}  ↗",21f,accent,true).apply {layoutParams=LinearLayout.LayoutParams(0,-2,1f);gravity=Gravity.END}
                label(hero,next.destination,16f,ink,true)
                space(hero,8)
                label(hero,"${CommuteRules.time(next.expected)} expected · ${repo.settings.walk} min walk" + if(stale) " · cached" else "",12f,if(stale) amber else muted)
            } else {
                label(hero,if(expired) "Refresh required" else "No catchable bus",24f,ink)
                if(expired) {space(hero,8);label(hero,"Data is over 10 minutes old.",12f,muted)}
            }
        }
        val arrivals=card(boardContent)
        label(arrivals,if(mode=="morning") repo.settings.stopName else "London Bridge → East Croydon",15f,ink,true)
        space(arrivals,5)
        label(arrivals,if(mode=="morning") "Northbound · ${repo.settings.routes.replace(",", " / ")} · ${repo.settings.walk} min walk" else "Fast services · scheduled journey ≤20 min",11f,muted)
        val departures=b?.takeIf {!expired}?.departures?.filter {(it.expected?:it.scheduled)?.isAfter(now.minusSeconds(60))!=false}?.take(4).orEmpty()
        if(departures.isEmpty()) {divider(arrivals);label(arrivals,if(expired) "Data expired. Refresh required." else if(kind!="bus"&&repo.error(kind).startsWith("Rail not configured")) "Rail access is not configured." else repo.error(kind).ifBlank {"No upcoming departures in this feed."},13f,muted)}
        for(d in departures) {
            divider(arrivals)
            val line=row(arrivals)
            label(line,d.route,13f,accent,true).apply {gravity=Gravity.CENTER;background=shape(Color.rgb(30,38,58),radius=8f);setPadding(dp(8),dp(8),dp(8),dp(8))}
            val detail=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(8),0);line.addView(this,LinearLayout.LayoutParams(0,-2,1f))}
            label(detail,d.destination,13f,ink,true)
            space(detail,5)
            label(detail,(d.expected?:d.scheduled)?.let {CommuteRules.time(it)+if(d.expected!=null) " expected" else " scheduled"}?:"No prediction",11f,muted)
            val mins=d.expected?.let {CommuteRules.minutesRemaining(it,now)}
            val state=when {d.status=="Cancelled"->"Cancelled";mins==null->d.status;mins<=0->"Elapsed";else->"~${mins}m"}
            val right=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.END;line.addView(this,LinearLayout.LayoutParams(-2,-2))}
            label(right,state,18f,if(stale||mins!=null&&mins<repo.settings.walk) amber else ink,true)
            val detailText=if(d.duration>0) "${d.duration}m · P${d.platform.ifBlank {"—"}}" else if(mins!=null&&mins<=0) "refresh" else if(mins!=null&&mins<repo.settings.walk) "too soon" else ""
            if(detailText.isNotEmpty()) {space(right,4);label(right,detailText,10f,muted)}
            if(d.duration>0&&d.expected!=null&&d.scheduled!=null&&d.status!="Cancelled") {
                val delay=Duration.between(d.scheduled,d.expected).toMinutes()
                if(delay>0) {space(arrivals,6);label(arrivals,"+${delay}m late · normally ${d.duration} min",11f,amber)}
            }
        }
        if(b!=null) {divider(arrivals);label(arrivals,"${if(stale||expired) "STALE · " else ""}${b.source} checked ${CommuteRules.time(b.fetched)}"+if(age!=null) " · ${age/60}m ago" else "",11f,if(stale||expired) amber else muted)}
        if(b!=null&&(repo.error(kind).isNotBlank()||b.warning.isNotBlank())) {space(arrivals,6);label(arrivals,if(expired) "Reconnect and refresh to fetch new data." else "Refresh unavailable. Showing cached estimates.",11f,amber)}
        if(mode=="morning") {
            val rail=card(boardContent)
            label(rail,"East Croydon  →  London Bridge",15f,ink,true)
            space(rail,8)
            val configured=!repo.error("out").startsWith("Rail not configured")
            label(rail,if(configured) repo.summary("out",2,true) else "Rail not connected",13f,if(configured) muted else amber)
            if(!configured){space(rail,6);label(rail,"Add rail credentials on the backend.",12f,muted)}
        }
    }
    private fun refresh() {
        if(busy) return; busy=true; render()
        val selected=mode
        io.execute {
            val repo=Repository(this)
            for(kind in if(selected=="morning") listOf("bus","out") else listOf("home")) { try { repo.refresh(kind) } catch(e:Exception) {/* Repository retains the error beside its original cache key. */} }
            runOnUiThread { if(!isDestroyed) {busy=false; render(); CommuteWidget.update(this)} }
        }
    }
    private fun toast(s:String) {Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
    private fun settings() {
        settingsOpen=true;root();button("‹  Back to departures") {home()};space(content,18);text("Settings",30f);space(content,12)
        val s=Settings(this);val fields=linkedMapOf<String,EditText>()
        fun field(key:String,label:String,default:String,secret:Boolean=false) {
            val heading=when(key){"base"->"01   CONNECTION";"stop"->"02   JOURNEY";"days"->"03   SCHEDULE";else->null}
            if(heading!=null){space(content,20);text(heading,11f).apply {setTextColor(accent);letterSpacing=.12f}}
            text(label,12f).setTextColor(muted)
            fields[key]=EditText(this).apply {
                setText(s.get(key,default));setSingleLine();setTextColor(ink);textSize=15f;minHeight=dp(50)
                setPadding(dp(14),dp(10),dp(14),dp(10));background=shape(Color.rgb(22,24,30),radius=10f)
                contentDescription=label
                inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                content.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(6)})
            }
        }
        field("base","Backend URL","")
        field("token","Backend access token","",true)
        field("stop","Bus stop ID","490007669N")
        field("stopName","Stop label","Haling Park Gardens · GW")
        field("routes","Bus routes, comma separated","166,405")
        field("walk","Walk to stop (minutes)","3")
        field("days","Commute days · 1 Mon, 2 Tue … 7 Sun","1,4")
        field("morningStart","Morning start, HH:mm","07:00");field("morningEnd","Morning end, HH:mm","08:30")
        field("eveningStart","Afternoon start, HH:mm","16:00");field("eveningEnd","Afternoon end, HH:mm","19:00")
        val notifications=CheckBox(this).apply {text="Silent lock-screen notification";isChecked=s.notifications;setTextColor(ink);buttonTintList=ColorStateList.valueOf(accent);setPadding(0,dp(16),0,dp(16));content.addView(this)}
        fun save():Boolean {
            try {
                val base=fields.getValue("base").text.toString().trim();val u=URI(base)
                require(u.host!=null&&u.userInfo==null&&u.rawQuery==null&&u.fragment==null&&(u.scheme=="https"||u.scheme=="http")) {"Enter a valid backend URL"}
                require(fields.getValue("walk").text.toString().toInt() in 0..60) {"Walking time must be 0–60"}
                require(fields.getValue("stop").text.toString().matches(Regex("[A-Za-z0-9]{5,30}"))) {"Select a valid stop"}
                val days=fields.getValue("days").text.toString().split(',').map {it.trim().toInt()};require(days.isNotEmpty()&&days.all {it in 1..7}) {"Days must be 1–7"}
                for(prefix in listOf("morning","evening")) {val start=LocalTime.parse(fields.getValue(prefix+"Start").text);val end=LocalTime.parse(fields.getValue(prefix+"End").text);require(start<end) {"Start time must be before end time"}}
                require(LocalTime.parse(fields.getValue("morningEnd").text)<=LocalTime.parse(fields.getValue("eveningStart").text)) {"Commute windows must not overlap"}
                val edit=s.prefs.edit();fields.forEach {(k,v)->edit.putString(k,v.text.toString().trim())};edit.putBoolean("notifications",notifications.isChecked).apply()
                return true
            } catch(e:Exception) {toast(e.message?:"Check your settings");return false}
        }
        button("Find a bus stop") {
            if(save()) {val query=EditText(this).apply {hint="Stop name"};AlertDialog.Builder(this).setTitle("Search TfL bus stops").setView(query).setPositiveButton("Search") {_,_->searchStops(query.text.toString(),fields)}.setNegativeButton("Cancel",null).show()}
        }
        button("Save settings") {
            if(save()) {
                if(notifications.isChecked && Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
                RefreshWorker.schedule(this);CommuteWidget.update(this);home();refresh()
            }
        }
        button("Back") {home()}
        text("Europe/London. Android may delay background updates.",13f)
    }
    private fun searchStops(query:String,fields:Map<String,EditText>) {
        toast("Searching stops…")
        io.execute {try {
            val a=JSONArray(Repository(this).request("/v1/stops?q="+encode(query)))
            val labels=(0 until a.length()).map {val o=a.getJSONObject(it);"${o.getString("name")} · ${o.optString("letter")}\n${o.getJSONArray("routes").join(", ")} · ${o.getString("id")}"}.toTypedArray()
            runOnUiThread {if(!isDestroyed) {if(labels.isEmpty()) toast("No stops found") else AlertDialog.Builder(this).setTitle("Choose direction / stop letter").setItems(labels) {_,i->val o=a.getJSONObject(i);fields.getValue("stop").setText(o.getString("id"));fields.getValue("stopName").setText(o.getString("name")+" · "+o.optString("letter"));toast("Check your route filter, then Save")}.show()}}
        }catch(e:Exception){runOnUiThread {if(!isDestroyed) toast(e.message?:"Search failed")}}}
    }
}
