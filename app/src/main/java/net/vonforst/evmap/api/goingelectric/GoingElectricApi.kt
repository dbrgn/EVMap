package net.vonforst.evmap.api.goingelectric

import android.content.Context
import com.car2go.maps.model.LatLng
import com.car2go.maps.model.LatLngBounds
import com.facebook.stetho.okhttp3.StethoInterceptor
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import net.vonforst.evmap.BuildConfig
import net.vonforst.evmap.R
import net.vonforst.evmap.api.ChargepointApi
import net.vonforst.evmap.api.StringProvider
import net.vonforst.evmap.api.nameForPlugType
import net.vonforst.evmap.model.*
import net.vonforst.evmap.ui.cluster
import net.vonforst.evmap.viewmodel.Resource
import net.vonforst.evmap.viewmodel.getClusterDistance
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import kotlin.math.abs

interface GoingElectricApi {
    @GET("chargepoints/")
    suspend fun getChargepoints(
        @Query("sw_lat") sw_lat: Double, @Query("sw_lng") sw_lng: Double,
        @Query("ne_lat") ne_lat: Double, @Query("ne_lng") ne_lng: Double,
        @Query("zoom") zoom: Float,
        @Query("clustering") clustering: Boolean = false,
        @Query("cluster_distance") clusterDistance: Int? = null,
        @Query("freecharging") freecharging: Boolean = false,
        @Query("freeparking") freeparking: Boolean = false,
        @Query("min_power") minPower: Int = 0,
        @Query("plugs") plugs: String? = null,
        @Query("chargecards") chargecards: String? = null,
        @Query("networks") networks: String? = null,
        @Query("categories") categories: String? = null,
        @Query("startkey") startkey: Int? = null,
        @Query("open_twentyfourseven") open247: Boolean = false,
        @Query("barrierfree") barrierfree: Boolean = false,
        @Query("exclude_faults") excludeFaults: Boolean = false
    ): Response<GEChargepointList>

    @GET("chargepoints/")
    suspend fun getChargepointsRadius(
        @Query("lat") lat: Double, @Query("lng") lng: Double,
        @Query("radius") radius: Int,
        @Query("zoom") zoom: Float,
        @Query("orderby") orderby: String = "distance",
        @Query("clustering") clustering: Boolean = false,
        @Query("cluster_distance") clusterDistance: Int? = null,
        @Query("freecharging") freecharging: Boolean = false,
        @Query("freeparking") freeparking: Boolean = false,
        @Query("min_power") minPower: Int = 0,
        @Query("plugs") plugs: String? = null,
        @Query("chargecards") chargecards: String? = null,
        @Query("networks") networks: String? = null,
        @Query("categories") categories: String? = null,
        @Query("startkey") startkey: Int? = null,
        @Query("open_twentyfourseven") open247: Boolean = false,
        @Query("barrierfree") barrierfree: Boolean = false,
        @Query("exclude_faults") excludeFaults: Boolean = false
    ): Response<GEChargepointList>

    @GET("chargepoints/")
    suspend fun getChargepointDetail(@Query("ge_id") id: Long): Response<GEChargepointList>

    @GET("chargepoints/pluglist/")
    suspend fun getPlugs(): Response<GEStringList>

    @GET("chargepoints/networklist/")
    suspend fun getNetworks(): Response<GEStringList>

    @GET("chargepoints/chargecardlist/")
    suspend fun getChargeCards(): Response<GEChargeCardList>

    companion object {
        private val cacheSize = 10L * 1024 * 1024 // 10MB

        private val moshi = Moshi.Builder()
            .add(ChargepointListItemJsonAdapterFactory())
            .add(JsonObjectOrFalseAdapter.Factory())
            .add(HoursAdapter())
            .add(InstantAdapter())
            .build()

        fun create(
            apikey: String,
            baseurl: String = "https://api.goingelectric.de",
            context: Context? = null
        ): GoingElectricApi {
            val client = OkHttpClient.Builder().apply {
                addInterceptor { chain ->
                    // add API key to every request
                    var original = chain.request()
                    val url = original.url.newBuilder().addQueryParameter("key", apikey).build()
                    original = original.newBuilder().url(url).build()
                    chain.proceed(original)
                }
                if (BuildConfig.DEBUG) {
                    addNetworkInterceptor(StethoInterceptor())
                }
                if (context != null) {
                    cache(Cache(context.getCacheDir(), cacheSize))
                }
            }.build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseurl)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .client(client)
                .build()
            return retrofit.create(GoingElectricApi::class.java)
        }
    }
}

class GoingElectricApiWrapper(
    val apikey: String,
    baseurl: String = "https://api.goingelectric.de",
    context: Context? = null
) : ChargepointApi<GEReferenceData> {
    val api = GoingElectricApi.create(apikey, baseurl, context)

    override fun getName() = "GoingElectric.de"

    override suspend fun getChargepoints(
        referenceData: ReferenceData,
        bounds: LatLngBounds,
        zoom: Float,
        filters: FilterValues
    ): Resource<List<ChargepointListItem>> {
        val freecharging = filters.getBooleanValue("freecharging")!!
        val freeparking = filters.getBooleanValue("freeparking")!!
        val open247 = filters.getBooleanValue("open_247")!!
        val barrierfree = filters.getBooleanValue("barrierfree")!!
        val excludeFaults = filters.getBooleanValue("exclude_faults")!!
        val minPower = filters.getSliderValue("min_power")!!
        val minConnectors = filters.getSliderValue("min_connectors")!!

        val connectorsVal = filters.getMultipleChoiceValue("connectors")!!
        if (connectorsVal.values.isEmpty() && !connectorsVal.all) {
            // no connectors chosen
            return Resource.success(emptyList())
        }
        connectorsVal.values = connectorsVal.values.mapNotNull {
            GEChargepoint.convertType(it)
        }.toMutableSet()
        val connectors = formatMultipleChoice(connectorsVal)

        val chargeCardsVal = filters.getMultipleChoiceValue("chargecards")!!
        if (chargeCardsVal.values.isEmpty() && !chargeCardsVal.all) {
            // no chargeCards chosen
            return Resource.success(emptyList())
        }
        val chargeCards = formatMultipleChoice(chargeCardsVal)

        val networksVal = filters.getMultipleChoiceValue("networks")!!
        if (networksVal.values.isEmpty() && !networksVal.all) {
            // no networks chosen
            return Resource.success(emptyList())
        }
        val networks = formatMultipleChoice(networksVal)

        val categoriesVal = filters.getMultipleChoiceValue("categories")!!
        if (categoriesVal.values.isEmpty() && !categoriesVal.all) {
            // no categories chosen
            return Resource.success(emptyList())
        }
        val categories = formatMultipleChoice(categoriesVal)

        // do not use clustering if filters need to be applied locally.
        val useClustering = zoom < 13
        val geClusteringAvailable = minConnectors <= 1
        val useGeClustering = useClustering && geClusteringAvailable
        val clusterDistance = if (useClustering) getClusterDistance(zoom) else null

        var startkey: Int? = null
        val data = mutableListOf<GEChargepointListItem>()
        do {
            // load all pages of the response
            try {
                val response = api.getChargepoints(
                    bounds.southwest.latitude,
                    bounds.southwest.longitude,
                    bounds.northeast.latitude,
                    bounds.northeast.longitude,
                    clustering = useGeClustering,
                    zoom = zoom,
                    clusterDistance = clusterDistance,
                    freecharging = freecharging,
                    minPower = minPower,
                    freeparking = freeparking,
                    open247 = open247,
                    barrierfree = barrierfree,
                    excludeFaults = excludeFaults,
                    plugs = connectors,
                    chargecards = chargeCards,
                    networks = networks,
                    categories = categories,
                    startkey = startkey
                )
                if (!response.isSuccessful || response.body()!!.status != "ok") {
                    return Resource.error(response.message(), null)
                } else {
                    val body = response.body()!!
                    data.addAll(body.chargelocations)
                    startkey = body.startkey
                }
            } catch (e: IOException) {
                return Resource.error(e.message, null)
            }
        } while (startkey != null && startkey < 10000)

        var result = data.filter { it ->
            // apply filters which GoingElectric does not support natively
            if (it is GEChargeLocation) {
                it.chargepoints
                    .filter { it.power >= minPower }
                    .filter { if (!connectorsVal.all) it.type in connectorsVal.values else true }
                    .sumBy { it.count } >= minConnectors
            } else {
                true
            }
        }.map { it.convert(apikey) }  // convert to common model
        if (!geClusteringAvailable && useClustering) {
            // apply local clustering if server side clustering is not available
            Dispatchers.IO.run {
                result = cluster(result, zoom, clusterDistance!!)
            }
        }

        return Resource.success(result)
    }

    private fun formatMultipleChoice(connectorsVal: MultipleChoiceFilterValue) =
        if (connectorsVal.all) null else connectorsVal.values.joinToString(",")

    override suspend fun getChargepointsRadius(
        referenceData: ReferenceData,
        location: LatLng,
        radius: Int,
        zoom: Float,
        filters: FilterValues
    ): Resource<List<ChargepointListItem>> {
        TODO("Not yet implemented")
    }

    override suspend fun getChargepointDetail(
        referenceData: ReferenceData,
        id: Long
    ): Resource<ChargeLocation> {
        val response = api.getChargepointDetail(id)
        return if (response.isSuccessful && response.body()!!.status == "ok" && response.body()!!.chargelocations.size == 1) {
            Resource.success(
                (response.body()!!.chargelocations[0] as GEChargeLocation).convert(
                    apikey
                )
            )
        } else {
            Resource.error(response.message(), null)
        }
    }

    override suspend fun getReferenceData(): Resource<GEReferenceData> =
        withContext(Dispatchers.IO) {
            val plugs = async { api.getPlugs() }
            val chargeCards = async { api.getChargeCards() }
            val networks = async { api.getNetworks() }

            val plugsResponse = plugs.await()
            val chargeCardsResponse = chargeCards.await()
            val networksResponse = networks.await()

            val responses = listOf(plugsResponse, chargeCardsResponse, networksResponse)

            if (responses.map { it.isSuccessful }.all { it }) {
                Resource.success(
                    GEReferenceData(
                        plugsResponse.body()!!.result,
                        networksResponse.body()!!.result,
                        chargeCardsResponse.body()!!.result
                    )
                )
            } else {
                Resource.error(responses.find { !it.isSuccessful }!!.message(), null)
            }
        }

    override fun getFilters(
        referenceData: ReferenceData,
        sp: StringProvider
    ): List<Filter<FilterValue>> {
        val referenceData = referenceData as GEReferenceData
        val plugs = referenceData.plugs
        val networks = referenceData.networks
        val chargeCards = referenceData.chargecards

        val plugMap = plugs.map { plug ->
            plug to nameForPlugType(sp, plug)
        }.toMap()
        val networkMap = networks.map { it to it }.toMap()
        val chargecardMap = chargeCards.map { it.id.toString() to it.name }.toMap()
        val categoryMap = mapOf(
            "Autohaus" to sp.getString(R.string.category_car_dealership),
            "Autobahnraststätte" to sp.getString(R.string.category_service_on_motorway),
            "Autohof" to sp.getString(R.string.category_service_off_motorway),
            "Bahnhof" to sp.getString(R.string.category_railway_station),
            "Behörde" to sp.getString(R.string.category_public_authorities),
            "Campingplatz" to sp.getString(R.string.category_camping),
            "Einkaufszentrum" to sp.getString(R.string.category_shopping_mall),
            "Ferienwohnung" to sp.getString(R.string.category_holiday_home),
            "Flughafen" to sp.getString(R.string.category_airport),
            "Freizeitpark" to sp.getString(R.string.category_amusement_park),
            "Hotel" to sp.getString(R.string.category_hotel),
            "Kino" to sp.getString(R.string.category_cinema),
            "Kirche" to sp.getString(R.string.category_church),
            "Krankenhaus" to sp.getString(R.string.category_hospital),
            "Museum" to sp.getString(R.string.category_museum),
            "Parkhaus" to sp.getString(R.string.category_parking_multi),
            "Parkplatz" to sp.getString(R.string.category_parking),
            "Privater Ladepunkt" to sp.getString(R.string.category_private_charger),
            "Rastplatz" to sp.getString(R.string.category_rest_area),
            "Restaurant" to sp.getString(R.string.category_restaurant),
            "Schwimmbad" to sp.getString(R.string.category_swimming_pool),
            "Supermarkt" to sp.getString(R.string.category_supermarket),
            "Tankstelle" to sp.getString(R.string.category_petrol_station),
            "Tiefgarage" to sp.getString(R.string.category_parking_underground),
            "Tierpark" to sp.getString(R.string.category_zoo),
            "Wohnmobilstellplatz" to sp.getString(R.string.category_caravan_site)
        )
        return listOf(
            BooleanFilter(sp.getString(R.string.filter_free), "freecharging"),
            BooleanFilter(sp.getString(R.string.filter_free_parking), "freeparking"),
            BooleanFilter(sp.getString(R.string.filter_open_247), "open_247"),
            SliderFilter(
                sp.getString(R.string.filter_min_power), "min_power",
                powerSteps.size - 1,
                mapping = ::mapPower,
                inverseMapping = ::mapPowerInverse,
                unit = "kW"
            ),
            MultipleChoiceFilter(
                sp.getString(R.string.filter_connectors), "connectors",
                plugMap,
                commonChoices = setOf(
                    Chargepoint.TYPE_2_UNKNOWN,
                    Chargepoint.CCS_UNKNOWN,
                    Chargepoint.CHADEMO
                ),
                manyChoices = true
            ),
            SliderFilter(
                sp.getString(R.string.filter_min_connectors),
                "min_connectors",
                10,
                min = 1
            ),
            MultipleChoiceFilter(
                sp.getString(R.string.filter_networks), "networks",
                networkMap, manyChoices = true
            ),
            MultipleChoiceFilter(
                sp.getString(R.string.categories), "categories",
                categoryMap,
                manyChoices = true
            ),
            BooleanFilter(sp.getString(R.string.filter_barrierfree), "barrierfree"),
            MultipleChoiceFilter(
                sp.getString(R.string.filter_chargecards), "chargecards",
                chargecardMap, manyChoices = true
            ),
            BooleanFilter(sp.getString(R.string.filter_exclude_faults), "exclude_faults")
        )
    }

    private val powerSteps = listOf(0, 2, 3, 7, 11, 22, 43, 50, 75, 100, 150, 200, 250, 300, 350)
    private fun mapPower(i: Int) = powerSteps[i]
    private fun mapPowerInverse(power: Int) = powerSteps
        .mapIndexed { index, v -> abs(v - power) to index }
        .minByOrNull { it.first }?.second ?: 0
}

