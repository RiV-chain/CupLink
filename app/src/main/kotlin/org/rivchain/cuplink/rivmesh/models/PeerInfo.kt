package org.rivchain.cuplink.rivmesh.models

import android.content.Context
import com.hbb20.CCPCountry
import com.hbb20.CountryCodePicker
import java.net.InetAddress

class PeerInfo {

    constructor()

    constructor(schema: String, address: InetAddress, port: Int, countryCode: String?, isMeshPeer: Boolean){
        this.schema = schema
        var a = address.toString();
        if(a.lastIndexOf('/')>0){
            this.hostName = a.split("/")[0]
        } else {
            this.hostName = a.substring(1)
        }
        this.port = port
        this.countryCode = countryCode
        this.isMeshPeer = isMeshPeer
    }

    lateinit var schema: String
    lateinit var hostName: String
    var port = 0
    var countryCode: String?=null
    var ping: Int = Int.MAX_VALUE
    var isMeshPeer = false

    override fun toString(): String {
        if(this.hostName.contains(":")) {
            return this.schema + "://[" + this.hostName + "]:" + port
        } else {
            return this.schema + "://" + this.hostName + ":" + port
        }
    }

    override fun equals(other: Any?): Boolean {
        return toString().equals(other.toString())
    }

    override fun hashCode(): Int {
        return toString().hashCode()
    }

    fun getCountry(context: Context): CCPCountry? {
        if(countryCode==null){
            return null
        } else {
            return CCPCountry.getCountryForNameCodeFromLibraryMasterList(
                context,
                CountryCodePicker.Language.ENGLISH,
                countryCode
            )
        }
    }
}