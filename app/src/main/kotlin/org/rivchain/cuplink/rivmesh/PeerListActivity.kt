package org.rivchain.cuplink.rivmesh

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.webkit.URLUtil
import android.widget.Button
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hbb20.BuildConfig
import com.hbb20.CCPCountry
import com.hbb20.CountryCodePicker
import com.vincentbrison.openlibraries.android.dualcache.Builder
import com.vincentbrison.openlibraries.android.dualcache.JsonSerializer
import com.vincentbrison.openlibraries.android.dualcache.SizeOf
import kotlinx.coroutines.*
import org.rivchain.cuplink.util.Log
import org.rivchain.cuplink.MainService
import org.rivchain.cuplink.R
import org.rivchain.cuplink.SettingsActivity
import org.rivchain.cuplink.rivmesh.util.Utils.Companion.deserializeStringList2PeerInfoSet
import org.rivchain.cuplink.rivmesh.util.Utils.Companion.ping
import org.rivchain.cuplink.rivmesh.models.PeerInfo
import org.rivchain.cuplink.rivmesh.models.Status
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.lang.reflect.Type
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.*

class PeerListActivity : AppCompatActivity(), ServiceConnection {

    internal var binder: MainService.MainBinder? = null

    companion object {
        const val PEER_LIST = "PEER_LIST"
        const val PEER_LIST_URL = "https://map.rivchain.org/rest/peers.json"
        const val CACHE_NAME = "PEER_LIST_CACHE"
        const val ONLINE_PEERINFO_LIST = "online_peer_info_list"
        const val OFFLINE_PEERINFO_LIST = "offline_peer_info_list"
        const val TEST_APP_VERSION = BuildConfig.VERSION_CODE
        const val RAM_MAX_SIZE = 100000
        const val DISK_MAX_SIZE = 100000
    }

    fun downloadJson(link: String): String {
        URL(link).openStream().use { input ->
            val outStream = ByteArrayOutputStream()
            outStream.use { output ->
                input.copyTo(output)
            }
            return String(outStream.toByteArray(), Charset.forName("UTF-8"))
        }
    }

    private var peerListUrl = PEER_LIST_URL
    private var peerListPing = true
    var popup: PopupWindow? = null
    var adapter: DropDownAdapter? = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peer_list)
        setSupportActionBar(findViewById(R.id.toolbar))

        bindService(Intent(this, MainService::class.java), this, 0)

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { _ ->
            addNewPeer()
        }
        val preferences =
            PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val peerListUrl: String =
            preferences.getString(PEER_LIST, "")!!
        if(peerListUrl.isNotBlank()){
            this@PeerListActivity.peerListUrl = peerListUrl
        }
        val extras = intent.extras
        val peerList = findViewById<ListView>(R.id.peerList)

        val alreadySelectedPeers = deserializeStringList2PeerInfoSet(extras!!.getStringArrayList(PEER_LIST)!!)
        val adapter = SelectPeerInfoListAdapter(this, arrayListOf(), alreadySelectedPeers)
        peerList.adapter = adapter
        val peerInfoListCache = Builder<List<PeerInfo>>(CACHE_NAME, TEST_APP_VERSION)
            .enableLog()
            .useReferenceInRam(RAM_MAX_SIZE, SizeOfPeerList())
            .useSerializerInDisk(
                DISK_MAX_SIZE, true,
                JsonSerializer(ArrayList<PeerInfo>().javaClass), baseContext
            ).build();

        GlobalScope.launch() {
            try {
                for (pi in alreadySelectedPeers) {
                    val ping = ping(pi.hostName, pi.port)
                    pi.ping = ping
                }
                try {
                    var peerInfoCache = peerInfoListCache.get(ONLINE_PEERINFO_LIST)
                    if (peerInfoCache != null && peerInfoCache.isNotEmpty()) {
                        for (peerInfo in peerInfoCache) {
                            val ping = ping(peerInfo.hostName, peerInfo.port)
                            peerInfo.ping = ping
                            if (alreadySelectedPeers.contains(peerInfo)) {
                                continue
                            }
                            withContext(Dispatchers.Main) {
                                adapter.addItem(peerInfo)
                                if (adapter.count % 5 == 0) {
                                    adapter.sort()
                                }
                            }
                        }
                    }
                    val json = downloadJson(this@PeerListActivity.peerListUrl)
                    val countries = CCPCountry.getLibraryMasterCountriesEnglish()
                    val mapType: Type = object :
                        TypeToken<Map<String?, Map<String, Status>>>() {}.type
                    val peersMap: Map<String, Map<String, Status>> = Gson().fromJson(json, mapType)
                    val cachePeerInfoList = mutableListOf<PeerInfo>()
                    for ((country, peers) in peersMap.entries) {
                        for ((peer, status) in peers) {
                            if (status.up) {
                                for (ccp in countries) {
                                    if (ccp.name.lowercase(Locale.getDefault())
                                            .contains(country.replace(".md", "").replace("-", " "))
                                    ) {
                                        if(!peerListPing){
                                            return@launch
                                        }
                                        val url = URI(peer)
                                        try {
                                            val address =
                                                withContext(Dispatchers.IO) {
                                                    InetAddress.getByName(url.host)
                                                }
                                            val peerInfo =
                                                PeerInfo(
                                                    url.scheme,
                                                    address,
                                                    url.port,
                                                    ccp.nameCode,
                                                    false
                                                )
                                            val ping = ping(url.host, url.port)
                                            peerInfo.ping = ping
                                            if (alreadySelectedPeers.contains(peerInfo)) {
                                                continue
                                            }
                                            if (peerInfo.ping < Int.MAX_VALUE) {
                                                cachePeerInfoList.add(peerInfo)
                                            }
                                            withContext(Dispatchers.Main) {
                                                adapter.addItem(peerInfo)
                                                if (adapter.count % 5 == 0) {
                                                    adapter.sort()
                                                    if (cachePeerInfoList.size > 0) {
                                                        peerInfoListCache.put(
                                                            ONLINE_PEERINFO_LIST,
                                                            cachePeerInfoList.toList()
                                                        )
                                                    }
                                                }
                                            }
                                        } catch (e: Throwable) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    when (e) {
                        is FileNotFoundException, is UnknownHostException -> {
                            val onlinePeerInfoList = peerInfoListCache.get(ONLINE_PEERINFO_LIST)
                            if (onlinePeerInfoList != null) {
                                for (peerInfo in onlinePeerInfoList) {
                                    val ping = ping(peerInfo.hostName, peerInfo.port)
                                    peerInfo.ping = ping
                                    if (alreadySelectedPeers.contains(peerInfo)) {
                                        continue
                                    }
                                    withContext(Dispatchers.Main) {
                                        adapter.addItem(peerInfo)
                                        if (adapter.count % 5 == 0) {
                                            adapter.sort()
                                        }
                                    }
                                }
                            }
                            e.printStackTrace()
                        }
                        else -> e.printStackTrace()
                    }
                }
                val currentPeers = ArrayList(alreadySelectedPeers.sortedWith(compareBy { it.ping }))
                withContext(Dispatchers.Main) {
                    adapter.addAll(0, currentPeers)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun editPeerListUrl() {
        val view: View = LayoutInflater.from(this).inflate(R.layout.edit_peer_list_url_dialog, null)
        val ab: AlertDialog.Builder = AlertDialog.Builder(this)
        ab.setCancelable(true).setView(view)
        val ad = ab.show()
        val saveButton = view.findViewById<Button>(R.id.save)
        val urlInput = view.findViewById<TextView>(R.id.urlInput)
        urlInput.text = peerListUrl
        saveButton.setOnClickListener{

            val url = urlInput.text.toString()
            if(!URLUtil.isValidUrl(url)){
                urlInput.error = "The URL is invalid!"
                return@setOnClickListener;
            }
            peerListUrl = url
            val preferences =
                PreferenceManager.getDefaultSharedPreferences(this.baseContext)
            preferences.edit().putString(PEER_LIST, peerListUrl).apply()
            ad.dismiss()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun addNewPeer() {
        val view: View = LayoutInflater.from(this).inflate(R.layout.new_peer_dialog, null)
        val countryCode: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            this.resources.configuration.locales[0].country
        } else {
            this.resources.configuration.locale.country
        }
        val schemaInput = view.findViewById<TextView>(R.id.schemaInput)
        val ipInput = view.findViewById<TextView>(R.id.ipInput)
        ipInput.requestFocus()
        schemaInput.showSoftInputOnFocus = false
        schemaInput.setOnFocusChangeListener { v, _ ->
            if(schemaInput.isFocused) {
                onClickSchemaList(v)
            }
        }
        schemaInput.setOnClickListener { v->
            onClickSchemaList(v)
        }
        getPopupWindow(
            R.layout.spinner_item,
            resources.getStringArray(R.array.schemas),
            schemaInput
        );
        view.findViewById<com.hbb20.CountryCodePicker>(R.id.ccp).setCountryForNameCode(countryCode)
        val ab: AlertDialog.Builder = AlertDialog.Builder(this)
        ab.setCancelable(true).setView(view)
        val ad = ab.show()
        val addButton = view.findViewById<Button>(R.id.add)
        addButton.setOnClickListener{
            val portInput = view.findViewById<TextView>(R.id.portInput)
            val ccpInput = view.findViewById<CountryCodePicker>(R.id.ccp)
            val schema = schemaInput.text.toString().lowercase(Locale.ROOT)
            if(schema.isEmpty()){
                schemaInput.error = "Schema is required"
            }
            val ip = ipInput.text.toString().lowercase(Locale.ROOT)
            if(ip.isEmpty()){
                ipInput.error = "IP address is required"
            }
            val port = portInput.text.toString().toInt()
            if(port<=0){
                portInput.error = "Port should be > 0"
            }
            if(port>=Short.MAX_VALUE){
                portInput.error = "Port should be < "+Short.MAX_VALUE
            }
            val ccp = ccpInput.selectedCountryNameCode
            GlobalScope.launch {
                val pi = PeerInfo(schema,
                    withContext(Dispatchers.IO) {
                        InetAddress.getByName(ip)
                    }, port, ccp, false)
                try {
                    val ping = ping(pi.hostName, pi.port)
                    pi.ping = ping
                } catch (e: Throwable){
                    pi.ping = Int.MAX_VALUE
                }
                withContext(Dispatchers.Main) {
                    val selectAdapter = (findViewById<ListView>(R.id.peerList).adapter as SelectPeerInfoListAdapter)
                    selectAdapter.addItem(0, pi)
                    selectAdapter.notifyDataSetChanged()
                    ad.dismiss()
                }
            }
        }
    }

    private fun onClickSchemaList(v: View) {
        val height = -1 * v.height +30
        getAddressListPopup()?.showAsDropDown(v, -5, height)
    }

    private fun getAddressListPopup(): PopupWindow? {
        return popup
    }

    private fun getPopupWindow(
        textViewResourceId: Int,
        objects: Array<String>,
        editText: TextView
    ): PopupWindow {
        // initialize a pop up window type
        val popupWindow = PopupWindow(this)
        // the drop down list is a list view
        val listView = ListView(this)
        listView.dividerHeight = 0
        // set our adapter and pass our pop up window contents
        adapter = DropDownAdapter(this, textViewResourceId, objects, popupWindow, editText)
        listView.adapter = adapter
        // set the item click listener
        listView.onItemClickListener = adapter
        // some other visual settings
        popupWindow.isFocusable = true
        popupWindow.width = 320
        popupWindow.height = WindowManager.LayoutParams.WRAP_CONTENT
        // set the list view as pop up window content
        popupWindow.contentView = listView
        popup = popupWindow
        return popupWindow
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.save_peers, menu)
        val item = menu.findItem(R.id.saveItem) as MenuItem
        item.setActionView(R.layout.menu_save)
        val saveButton = item
            .actionView?.findViewById<Button>(R.id.saveButton)
        saveButton?.setOnClickListener {
            saveButton.isClickable = false
            cancelPeerListPing()
            val result = Intent(this, SettingsActivity::class.java)
            val adapter = findViewById<ListView>(R.id.peerList).adapter as SelectPeerInfoListAdapter
            val selectedPeers = adapter.getSelectedPeers()
            binder!!.getMesh().updateJSON { json ->
                val a = json.getJSONArray("Peers")
                val l = a.length()
                var i = 0
                while (i < l) {
                    a.remove(0)
                    i++
                }
                for (peer in selectedPeers){
                    a.put(peer.toString())
                }
            }
            binder!!.saveDatabase()
            // Restart service
            val intentStop = Intent(this, MainService::class.java)
            intentStop.action = MainService.ACTION_STOP
            startService(intentStop)

            Thread {
                Thread.sleep(3000)
                val intentStart = Intent(this, MainService::class.java)
                intentStart.action = MainService.ACTION_START
                startService(intentStart)
                finish()
            }.start()
        }

        val editUrl = menu.findItem(R.id.editUrlItem) as MenuItem
        editUrl.setActionView(R.layout.menu_edit_url)
        val editUrlButton = editUrl
            .actionView?.findViewById<Button>(R.id.editUrlButton)
        editUrlButton?.setOnClickListener {
            editPeerListUrl()
        }
        return true
    }

    private fun cancelPeerListPing() {
        peerListPing = false
    }

    override fun onStop() {
        super.onStop()
        cancelPeerListPing()
    }

    override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
        Log.d(this, "onServiceConnected()")
        binder = iBinder as MainService.MainBinder
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        //nothing_todo
    }
}

class SizeOfPeerList: SizeOf<List<PeerInfo>> {

    override fun sizeOf(obj: List<PeerInfo>): Int{
        var size = 0
        for (o in obj) {
            if (o.hostName != null) {
                size += o.hostName.length * 2
            }
            if (o.schema != null) {
                size += o.schema.length * 2
            }
            if (o.countryCode != null) {
                size += o.countryCode!!.length * 2
            }
            size += 4
            size += 4
            size += 1
        }
        return size
    }
}