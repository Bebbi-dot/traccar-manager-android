/*
 * Copyright 2016 - 2023 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("DEPRECATION")
package org.traccar.manager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.Fragment
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager

class MainFragment : Fragment() {

    lateinit var webView: WebView
    private lateinit var broadcastManager: LocalBroadcastManager

    inner class AppInterface {
        @JavascriptInterface
        fun postMessage(message: String) {
            val act = activity ?: return
            if (message.startsWith("login")) {
                if (message.length > 6) {
                    SecurityManager.saveToken(act, message.substring(6))
                }
                broadcastManager.sendBroadcast(Intent(EVENT_LOGIN))
            } else if (message == "compass_ready") {
                act.runOnUiThread { injectCompassJS() }
            } else if (message.startsWith("authentication")) {
                SecurityManager.readToken(act) { token ->
                    if (token != null) {
                        val code = "handleLoginToken && handleLoginToken('$token')"
                        webView.evaluateJavascript(code, null)
                    }
                }
            } else if (message.startsWith("logout")) {
                SecurityManager.deleteToken(act)
            } else if (message.startsWith("server")) {
                val url = message.substring(7)
                PreferenceManager.getDefaultSharedPreferences(act)
                    .edit().putString(MainActivity.PREFERENCE_URL, url).apply()
                act.runOnUiThread { loadPage() }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        webView = WebView(activity)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return webView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        broadcastManager = LocalBroadcastManager.getInstance(activity)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (view !is WebView) return

        val act = activity ?: return
        if ((act.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        webView.setDownloadListener(downloadListener)
        webView.addJavascriptInterface(AppInterface(), "appInterface")
        // CompassInterface ins WebView injizieren
        if (act is MainActivity) {
            webView.addJavascriptInterface(act.CompassInterface(), "compassInterface")
        }
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.setSupportMultipleWindows(true)
        loadPage()
    }

    private fun loadPage() {
        val act = activity ?: return
        val url = PreferenceManager.getDefaultSharedPreferences(act)
            .getString(MainActivity.PREFERENCE_URL, null)
        if (url != null) {
            if (act is MainActivity) {
                val eventId = act.pendingEventId
                act.pendingEventId = null
                if (eventId != null) {
                    webView.loadUrl("$url?eventId=$eventId")
                    return
                }
            }
            webView.loadUrl(url)
        } else {
            act.fragmentManager
                .beginTransaction().replace(R.id.webview_container, StartFragment())
                .commit()
        }
    }

    private val tokenBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val token = intent.getStringExtra(KEY_TOKEN)
            val code = "updateNotificationToken && updateNotificationToken('$token')"
            webView.evaluateJavascript(code, null)
        }
    }

    private val eventIdBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadPage()
        }
    }

    override fun onStart() {
        super.onStart()
        val act = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.POST_NOTIFICATIONS)) {
                ActivityCompat.requestPermissions(
                    act,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_PERMISSIONS_NOTIFICATION
                )
            }
        }
        broadcastManager.registerReceiver(tokenBroadcastReceiver, IntentFilter(EVENT_TOKEN))
        broadcastManager.registerReceiver(eventIdBroadcastReceiver, IntentFilter(EVENT_EVENT))
    }

    override fun onStop() {
        super.onStop()
        broadcastManager.unregisterReceiver(tokenBroadcastReceiver)
        broadcastManager.unregisterReceiver(eventIdBroadcastReceiver)
    }

    private var openFileCallback: ValueCallback<Uri?>? = null
    private var openFileCallback2: ValueCallback<Array<Uri>>? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            val result = if (resultCode != Activity.RESULT_OK) null else data?.data
            if (openFileCallback != null) {
                openFileCallback?.onReceiveValue(result)
                openFileCallback = null
            }
            if (openFileCallback2 != null) {
                openFileCallback2?.onReceiveValue(if (result != null) arrayOf(result) else arrayOf())
                openFileCallback2 = null
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSIONS_LOCATION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (geolocationCallback != null) {
                geolocationCallback?.invoke(geolocationRequestOrigin, granted, false)
                geolocationRequestOrigin = null
                geolocationCallback = null
            }
        }
    }

    private var geolocationRequestOrigin: String? = null
    private var geolocationCallback: GeolocationPermissions.Callback? = null

    // Wird aufgerufen sobald Traccar-User eingeloggt ist (via AppInterface.postMessage("compass_ready"))
    fun injectCompassJS() {
        webView.evaluateJavascript("""
            (function() {
                if (document.getElementById('lyra-compass-btn')) return;

                // === localStorage ===
                var BTN_KEY = 'lyra_compass_btn_pos';
                var OVL_KEY = 'lyra_compass_ovl_pos';
                var MY_DEV_KEY = 'lyra_compass_my_device';
                function loadPos(k) { try { var s = localStorage.getItem(k); return s ? JSON.parse(s) : null; } catch(e) { return null; } }
                function savePos(k, d) { try { localStorage.setItem(k, JSON.stringify(d)); } catch(e) {} }

                // === Eigenes Geraet ===
                var savedDev = loadPos(MY_DEV_KEY);
                window.__myDeviceId = savedDev ? savedDev.id : null;
                window.__myDeviceName = savedDev ? savedDev.name : null;
                window.__myLat = null;
                window.__myLon = null;
                window.__targetLat = null;
                window.__targetLon = null;

                // === Proj Helper: EPSG:3857 -> WGS84 fallback falls ol.proj nicht verfuegbar ===
                function toLonLat(coords) {
                    if (window.ol && ol.proj && ol.proj.toLonLat) return ol.proj.toLonLat(coords);
                    var lon = coords[0] / 20037508.342789244 * 180;
                    var lat = Math.atan(Math.exp(coords[1] / 20037508.342789244 * Math.PI)) * 360 / Math.PI - 90;
                    return [lon, lat];
                }

                // === Haversine ===
                function bearingDistance(lat1, lon1, lat2, lon2) {
                    var R = 6371000;
                    var p1 = lat1 * Math.PI / 180, p2 = lat2 * Math.PI / 180;
                    var dp = (lat2 - lat1) * Math.PI / 180;
                    var dl = (lon2 - lon1) * Math.PI / 180;
                    var a = Math.sin(dp/2)*Math.sin(dp/2) + Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
                    var dist = R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
                    var y = Math.sin(dl)*Math.cos(p2);
                    var x = Math.cos(p1)*Math.sin(p2) - Math.sin(p1)*Math.cos(p2)*Math.cos(dl);
                    return {bearing: (Math.atan2(y, x) * 180 / Math.PI + 360) % 360, distance: dist};
                }

                // === Overlay ===
                var overlay = document.createElement('div');
                overlay.id = 'lyra-compass-overlay';
                overlay.innerHTML = '<canvas id="lyra-compass-canvas" width="220" height="260"></canvas>' +
                    '<div id="lyra-compass-dev-btn" style="width:100%;height:36px;line-height:36px;text-align:center;cursor:pointer;font-size:13px;color:#333;border-top:1px solid rgba(0,0,0,0.12);border-radius:0 0 12px 12px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;padding:0 8px;box-sizing:border-box;"></div>';
                overlay.style.cssText = 'position:fixed;z-index:2147483646;width:220px;height:296px;border-radius:16px;background:rgba(240,240,240,0.85);box-shadow:0 4px 12px rgba(0,0,0,0.3);display:block;cursor:grab;touch-action:none;user-select:none;';

                var canvas = overlay.querySelector('canvas');
                var ctx = canvas.getContext('2d');
                var devBtn = overlay.querySelector('#lyra-compass-dev-btn');
                var azimuth = 0, bearing = 0, distance = 0;

                function updateDevBtn() {
                    devBtn.textContent = window.__myDeviceName ? '📍 ' + window.__myDeviceName : '📍 Eigenes Gerät';
                }
                updateDevBtn();

                function draw() {
                    var cx = 110, cy = 130, r = 92;
                    ctx.clearRect(0,0,220,260);
                    // Kompass-Rose (nur Optik, unabhängig von der Nadel)
                    ctx.beginPath(); ctx.arc(cx,cy,r,0,Math.PI*2);
                    ctx.fillStyle = 'rgba(245,245,245,0.9)'; ctx.fill();
                    ctx.strokeStyle = '#555'; ctx.lineWidth = 2; ctx.stroke();
                    for (var d=0;d<360;d+=5) {
                        var rad = (d-azimuth-90)*Math.PI/180;
                        var m=d%45===0; var o=m?r-5:r-2; var i=m?r-18:r-10;
                        ctx.beginPath(); ctx.moveTo(cx+o*Math.cos(rad),cy+o*Math.sin(rad)); ctx.lineTo(cx+i*Math.cos(rad),cy+i*Math.sin(rad));
                        ctx.strokeStyle=m?'#222':'#999'; ctx.lineWidth=m?2:1; ctx.stroke();
                    }
                    var cards={0:'N',90:'E',180:'S',270:'W'};
                    Object.keys(cards).forEach(function(a) {
                        var rad=(parseInt(a)-azimuth-90)*Math.PI/180;
                        ctx.fillStyle=a==='0'?'#D32F2F':'#333'; ctx.font='bold 18px sans-serif';
                        ctx.textAlign='center'; ctx.textBaseline='middle';
                        ctx.fillText(cards[a], cx+(r-26)*Math.cos(rad), cy+(r-26)*Math.sin(rad));
                    });
                    // Nadel (Richtung Ziel, unabhängig von der Rose)
                    var na=(bearing-azimuth)*Math.PI/180;
                    ctx.save(); ctx.translate(cx,cy); ctx.rotate(na);
                    ctx.beginPath(); ctx.moveTo(0,-r*0.85); ctx.lineTo(-7,0); ctx.lineTo(0,-r*0.25); ctx.lineTo(7,0); ctx.closePath();
                    ctx.fillStyle='#D32F2F'; ctx.fill();
                    ctx.beginPath(); ctx.moveTo(0,r*0.5); ctx.lineTo(-7,0); ctx.lineTo(0,5); ctx.lineTo(7,0); ctx.closePath();
                    ctx.fillStyle='#666'; ctx.fill();
                    ctx.beginPath(); ctx.arc(0,0,5,0,Math.PI*2); ctx.fillStyle='#1976D2'; ctx.fill();
                    ctx.restore();
                    // Distanz
                    var dt=distance>=1000?(distance/1000).toFixed(1)+' km':Math.round(distance)+' m';
                    ctx.fillStyle='#333'; ctx.font='bold 16px sans-serif'; ctx.textAlign='center'; ctx.textBaseline='top';
                    ctx.fillText(dt, cx, cy+r+20);
                }

                // === Device Picker Popup ===
                var picker = document.createElement('div');
                picker.id = 'lyra-compass-picker';
                picker.style.cssText = 'position:fixed;z-index:2147483647;background:white;border-radius:12px;box-shadow:0 4px 16px rgba(0,0,0,0.3);padding:8px 0;max-height:300px;overflow-y:auto;display:none;min-width:180px;';
                document.body.appendChild(picker);

                devBtn.addEventListener('click', function(e) {
                    e.stopPropagation();
                    if (picker.style.display !== 'none') { picker.style.display = 'none'; return; }
                    try {
                        var store = Ext.getStore('Devices');
                        if (!store) return;
                        picker.innerHTML = '';
                        store.each(function(rec) {
                            var item = document.createElement('div');
                            item.textContent = rec.get('name');
                            item.style.cssText = 'padding:10px 16px;cursor:pointer;font-size:14px;color:#333;';
                            item.addEventListener('mouseenter', function() { item.style.background = '#f0f0f0'; });
                            item.addEventListener('mouseleave', function() { item.style.background = ''; });
                            item.addEventListener('click', function() {
                                window.__myDeviceId = rec.get('id');
                                window.__myDeviceName = rec.get('name');
                                savePos(MY_DEV_KEY, {id: window.__myDeviceId, name: window.__myDeviceName});
                                updateDevBtn();
                                picker.style.display = 'none';
                                attachPositionListener();
                            });
                            picker.appendChild(item);
                        });
                        var br = overlay.getBoundingClientRect();
                        picker.style.left = br.left + 'px';
                        picker.style.top = (br.bottom + 4) + 'px';
                        picker.style.display = 'block';
                        setTimeout(function() {
                            var ph = picker.offsetHeight;
                            if (parseInt(picker.style.top) + ph > window.innerHeight) {
                                picker.style.top = (br.top - ph - 4) + 'px';
                            }
                        }, 0);
                    } catch(ex) {}
                });
                document.addEventListener('click', function() { picker.style.display = 'none'; });

                // === Button ===
                var btn = document.createElement('div');
                btn.id = 'lyra-compass-btn';
                btn.innerHTML = '🧭';
                btn.style.cssText = 'position:fixed;z-index:2147483647;width:44px;height:44px;border-radius:8px;background:rgba(255,50,50,0.9);border:3px solid white;cursor:grab;font-size:24px;display:flex;align-items:center;justify-content:center;color:white;box-shadow:0 2px 8px rgba(0,0,0,0.3);user-select:none;touch-action:none;';

                btn.style.top='100px'; btn.style.right='12px';
                overlay.style.top='150px'; overlay.style.right='12px';

                // === Drag ===
                var ox=0, oy=0, dragging=false, dragEl=null;
                function startDrag(e, el) {
                    dragging=true; dragEl=el;
                    ox=(e.clientX||e.touches[0].clientX)-el.getBoundingClientRect().left;
                    oy=(e.clientY||e.touches[0].clientY)-el.getBoundingClientRect().top;
                    el.style.cursor='grabbing'; e.preventDefault();
                }
                function moveDrag(e) {
                    if(!dragging||!dragEl)return;
                    var cx=e.clientX||(e.touches&&e.touches[0].clientX);
                    var cy=e.clientY||(e.touches&&e.touches[0].clientY);
                    if(!cx||!cy)return;
                    dragEl.style.left=(cx-ox)+'px'; dragEl.style.top=(cy-oy)+'px';
                    dragEl.style.right='auto'; dragEl.style.bottom='auto';
                }
                function endDrag() {
                    if(dragging&&dragEl) {
                        dragEl.style.cursor='grab';
                    }
                    dragging=false; dragEl=null;
                }
                btn.addEventListener('mousedown',function(e){startDrag(e,btn);});
                overlay.addEventListener('mousedown',function(e){if(e.target===devBtn)return;startDrag(e,overlay);});
                btn.addEventListener('touchstart',function(e){startDrag(e,btn);},{passive:true});
                overlay.addEventListener('touchstart',function(e){if(e.target===devBtn)return;startDrag(e,overlay);},{passive:true});
                document.addEventListener('mousemove',moveDrag);
                document.addEventListener('touchmove',moveDrag,{passive:true});
                document.addEventListener('mouseup',endDrag);
                document.addEventListener('touchend',endDrag);

                btn.addEventListener('click',function(e){
                    if(dragging)return;
                    if(overlay.style.display==='none') {
                        var br=btn.getBoundingClientRect();
                        var ovlW=220, gap=8;
                        var leftPos=br.right+gap;
                        if(leftPos+ovlW>window.innerWidth) leftPos=br.left-ovlW-gap;
                        overlay.style.left=leftPos+'px';
                        overlay.style.top=br.top+'px';
                        overlay.style.right='auto'; overlay.style.bottom='auto';
                        overlay.style.display='block'; draw();
                        if(window.compassInterface) window.compassInterface.startCompass();
                    } else {
                        overlay.style.display='none';
                        picker.style.display='none';
                        if(window.compassInterface) window.compassInterface.stopCompass();
                    }
                });

                document.body.appendChild(btn);
                document.body.appendChild(overlay);
                draw();
                if (window.compassInterface) window.compassInterface.startCompass();

                // === LatestPositions Listener fuer eigene Position ===
                var posListenerAttached = false;
                function attachPositionListener() {
                    if (!window.__myDeviceId) return;
                    try {
                        var store = Ext.getStore('LatestPositions');
                        if (!store) return;
                        if (!posListenerAttached) {
                            store.on('update', function(st, record) {
                                if (record.get('deviceId') !== window.__myDeviceId) return;
                                window.__myLat = record.get('latitude');
                                window.__myLon = record.get('longitude');
                            });
                            posListenerAttached = true;
                        }
                        store.each(function(rec) {
                            if (rec.get('deviceId') === window.__myDeviceId) {
                                window.__myLat = rec.get('latitude');
                                window.__myLon = rec.get('longitude');
                            }
                        });
                    } catch(e) {}
                }

                // === Diagnose ===
                setTimeout(function() {
                    try {
                        console.log('compass: test Ext - typeof Ext=' + typeof Ext + ' CQ=' + typeof Ext.ComponentQuery);
                        console.log('compass: test Ext.ComponentQuery query devicesView - ' + (Ext.ComponentQuery.query('devicesView').length) + ' results');
                        console.log('compass: test Ext.ComponentQuery query [store=VisibleDevices] - ' + (Ext.ComponentQuery.query('[store=VisibleDevices]').length) + ' results');
                        console.log('compass: test Ext.ComponentQuery query grid - ' + (Ext.ComponentQuery.query('grid').length) + ' results');
                        console.log('compass: test Ext.getStore VisibleDevices - ' + (Ext.getStore('VisibleDevices') ? 'OK' : 'null'));
                        console.log('compass: test Ext.getStore LatestPositions - ' + (Ext.getStore('LatestPositions') ? 'OK' : 'null'));
                        console.log('compass: test Ext.getStore Devices - ' + (Ext.getStore('Devices') ? 'OK' : 'null'));
                    } catch(e) {
                        console.log('compass: test Ext crashed', e);
                    }
                }, 8000);

                // === Grid Selection Poll ===
                setInterval(function() {
                    try {
                        var grid = Ext.ComponentQuery.query('devicesView')[0] || Ext.ComponentQuery.query('[store=VisibleDevices]')[0];
                        if (!grid || !grid.getSelectionModel) return;
                        var selected = grid.getSelectionModel().getSelection();
                        if (!selected || selected.length === 0) return;
                        var rec = selected[0];
                        var devId = rec.get('id');
                        var devName = rec.get('name');
                        if (devId === window.__lastTargetDeviceId) return;
                        window.__lastTargetDeviceId = devId;
                        console.log('compass: selected devId=' + devId + ' name=' + devName);
                        var posStore = Ext.getStore('LatestPositions');
                        if (!posStore) { console.log('compass: no LatestPositions store'); return; }
                        var found = false;
                        posStore.each(function(pos) {
                            if (pos.get('deviceId') === devId) {
                                window.__targetLat = pos.get('latitude');
                                window.__targetLon = pos.get('longitude');
                                found = true;
                                console.log('compass: setTarget ' + devName + ' lat=' + window.__targetLat + ' lon=' + window.__targetLon);
                                if (window.compassInterface) window.compassInterface.setTarget(window.__targetLat, window.__targetLon, devName);
                            }
                        });
                        if (!found) console.log('compass: no position for device ' + devName);
                    } catch(e) {
                        console.log('compass: poll err ' + e.message);
                    }
                }, 500);
                attachPositionListener();
                console.log('compass: dom-poll started');

                // === Update Loop ===
                var lastAz=undefined, lastBr=undefined, lastDst=undefined;
                setInterval(function(){
                    var changed=false;
                    if(window.__compassAzimuth!==undefined&&window.__compassAzimuth!==lastAz){lastAz=window.__compassAzimuth;azimuth=lastAz;changed=true;}
                    if(window.__myLat&&window.__myLon&&window.__targetLat&&window.__targetLon) {
                        var bd=bearingDistance(window.__myLat,window.__myLon,window.__targetLat,window.__targetLon);
                        var newBr=Math.round(bd.bearing*10)/10, newDst=Math.round(bd.distance);
                        if(newBr!==lastBr||newDst!==lastDst){lastBr=newBr;lastDst=newDst;bearing=bd.bearing;distance=bd.distance;changed=true;}
                    } else {
                        if(window.__compassBearing!==undefined&&window.__compassBearing!==lastBr){lastBr=window.__compassBearing;bearing=lastBr;changed=true;}
                        if(window.__compassDistance!==undefined&&window.__compassDistance!==lastDst){lastDst=window.__compassDistance;distance=lastDst;changed=true;}
                    }
                    if(changed&&overlay.style.display!=='none') {
                        console.log('compass: az='+Math.round(azimuth)+' br='+Math.round(bearing)+' dist='+Math.round(distance)+
                            ' myPos='+window.__myLat+','+window.__myLon+' target='+window.__targetLat+','+window.__targetLon+
                            ' rawAz='+window.__compassAzimuth);
                        draw();
                    }
                },200);
            })();
        """.trimIndent(), null)
    }

        private val webViewClient = object : WebViewClient() {

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().flush()
            }
            // Minimaler Starter — wartet auf Login
            view?.evaluateJavascript(
                "if(typeof __lyraCompassInjected==='undefined'){window.__lyraCompassInjected=false;" +
                "var __lyraCheck=setInterval(function(){if(window.Traccar&&Traccar.app&&Traccar.app.getUser()){" +
                "clearInterval(__lyraCheck);window.appInterface.postMessage('compass_ready');}},500);}", null
            )
        }
    }

    private val webChromeClient = object : WebChromeClient() {

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val data = view.hitTestResult.extra
            return if (data != null) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(data))
                view.context.startActivity(browserIntent)
                true
            } else {
                false
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val act = activity ?: return
            geolocationRequestOrigin = null
            geolocationCallback = null
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    AlertDialog.Builder(act)
                        .setMessage(R.string.permission_location_rationale)
                        .setNeutralButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                            geolocationRequestOrigin = origin
                            geolocationCallback = callback
                            ActivityCompat.requestPermissions(
                                act,
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                REQUEST_PERMISSIONS_LOCATION
                            )
                        }
                        .show()
                } else {
                    geolocationRequestOrigin = origin
                    geolocationCallback = callback
                    ActivityCompat.requestPermissions(
                        act,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQUEST_PERMISSIONS_LOCATION
                    )
                }
            } else {
                callback.invoke(origin, true, false)
            }
        }

        // Android 4.1+
        @Suppress("UNUSED_PARAMETER")
        fun openFileChooser(uploadMessage: ValueCallback<Uri?>?, acceptType: String?, capture: String?) {
            val act = activity ?: return
            openFileCallback = uploadMessage
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.file_browser)),
                REQUEST_FILE_CHOOSER
            )
        }

        // Android 5.0+
        override fun onShowFileChooser(
            mWebView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            openFileCallback2?.onReceiveValue(null)
            openFileCallback2 = filePathCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val intent = fileChooserParams.createIntent()
                try {
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER)
                } catch (e: ActivityNotFoundException) {
                    openFileCallback2 = null
                    return false
                }
            }
            return true
        }
    }

    private val downloadListener = DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
        val act = activity ?: return@DownloadListener
        val request = DownloadManager.Request(Uri.parse(url))
        request.setMimeType(mimeType)
        request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
        request.allowScanningByMediaScanner()
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            URLUtil.guessFileName(url, contentDisposition, mimeType),
        )
        val downloadManager = act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
    }

    companion object {
        const val EVENT_LOGIN = "eventLogin"
        const val EVENT_TOKEN = "eventToken"
        const val EVENT_EVENT = "eventEvent"
        const val KEY_TOKEN = "keyToken"
        private const val REQUEST_PERMISSIONS_LOCATION = 1
        private const val REQUEST_PERMISSIONS_NOTIFICATION = 2
        private const val REQUEST_FILE_CHOOSER = 1
    }
}
