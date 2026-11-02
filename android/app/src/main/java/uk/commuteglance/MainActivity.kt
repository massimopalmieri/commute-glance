package uk.commuteglance

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
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
    private lateinit var output: TextView
    private var mode = "morning"
    private var busy = false
    private var settingsOpen = false
    private val tick = object : Runnable { override fun run() { if (!settingsOpen) render(); handler.postDelayed(this, 1000) } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(16,28,24); window.navigationBarColor = Color.rgb(16,28,24)
        mode = Settings(this).mode().let { if(it=="off") "morning" else it }
        RefreshWorker.schedule(this); home(); refresh()
        if (Settings(this).base.isBlank()) settings()
    }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
    private fun root() {
        val scroll = ScrollView(this); content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,32,24,32); setBackgroundColor(Color.rgb(16,28,24)) }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
    }
    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply { text=value;textSize=size;setTextColor(Color.rgb(222,239,227));setPadding(0,12,0,12);content.addView(this) }
    private fun button(label: String, action: () -> Unit) { content.addView(Button(this).apply { text=label;setOnClickListener { action() } }) }
    private fun home() {
        settingsOpen=false; root(); text("Commute Glance",28f)
        button(if(mode=="morning") "To work ↓  ·  Switch to homebound" else "Going home ↓  ·  Switch to morning") { mode=if(mode=="morning") "evening" else "morning";home();refresh() }
        output=text("",18f); button("↻ Refresh departures") { refresh() }
        button("Settings & stop picker") { settings() }
        button("Add home-screen widget") { val manager=getSystemService(AppWidgetManager::class.java); if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this,CommuteWidget::class.java),null,null) else toast("Long-press your home screen and choose Widgets") }
        text("Times are predictions. Cached estimates are not new live updates.\nData: TfL and National Rail.",12f); render()
    }
    private fun render() {
        if(!::output.isInitialized || settingsOpen) return
        val repo=Repository(this)
        output.text= (if(busy) "Refreshing…\n\n" else "") + if(mode=="morning")
            "BUS · ${repo.settings.stopName}\n${repo.summary("bus",4,true)}\n\nEAST CROYDON → LONDON BRIDGE\n${repo.summary("out",4,true)}"
        else "LONDON BRIDGE → EAST CROYDON\n${repo.summary("home",6,true)}"
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
        settingsOpen=true;root();text("Your commute",26f)
        val s=Settings(this);val fields=linkedMapOf<String,EditText>()
        fun field(key:String,label:String,default:String,secret:Boolean=false) {text(label,14f);fields[key]=EditText(this).apply {setText(s.get(key,default));setSingleLine();setTextColor(Color.WHITE);inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT;content.addView(this)} }
        field("base","Backend URL (HTTPS; debug builds allow local HTTP)","")
        field("token","Backend access token","",true)
        field("stop","Bus stop ID","490007669N")
        field("stopName","Stop label","Haling Park Gardens · GW")
        field("routes","Bus routes, comma separated","166,405")
        field("walk","Walk to stop (minutes)","3")
        field("days","Days: 1=Mon … 7=Sun, comma separated","1,4")
        field("morningStart","Morning start, HH:mm","07:00");field("morningEnd","Morning end, HH:mm","08:30")
        field("eveningStart","Afternoon start, HH:mm","16:00");field("eveningEnd","Afternoon end, HH:mm","19:00")
        val notifications=CheckBox(this).apply {text="Silent commute notification on lock screen";isChecked=s.notifications;setTextColor(Color.WHITE);content.addView(this)}
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
        text("All times use Europe/London. Background refresh is best effort; Android may delay it. Lock-screen visibility also depends on your system notification settings.",13f)
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
