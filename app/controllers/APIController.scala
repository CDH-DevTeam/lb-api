package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.ws._
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

import scala.util.Random
import scala.concurrent.{Await, Future}
import scala.collection.mutable.ListBuffer

import javax.inject._

import org.joda.time.DateTime
import org.joda.time.format._

case class DateSpan(startDate: DateTime, endDate: DateTime) {}
case class QueryFilter(key: String, terms: List[String]) {}
case class QueryTerm(originalTerm: String, analyzedTerm: String) {}
case class QueryObject(idSeq: String, queryTerms: ListBuffer[QueryTerm], queryFilters: ListBuffer[QueryFilter]) {}

@Singleton
class APIController @Inject() (ws: WSClient, config: play.api.Configuration) extends Controller {

	/*
	 *	Actions
	 */

	def getTimelineTotal(queryTranslated: Boolean) = Action.async {

		var startDate = new DateTime(config.getString("TL_START_DATE").get)
		var endDate = new DateTime(config.getString("TL_END_DATE").get)
		var aggCardWork: String = config.getString("WORK_FIELD_NAME").get
		var aggCardAuth: String = config.getString("AUTH_FIELD_NAME").get
		var aggTermFreq: String = config.getString("TERM_FREQ_FIELD_NAME").get

		var termFreqField: String = config.getString("TEXT_FIELD_ORIGINAL").get + ".length"
		if (queryTranslated) {
			termFreqField = config.getString("TEXT_FIELD_TRANSLATED").get + ".length"
		}

		var queryData = Json.obj(
			"size" -> 0,
			"query" -> Json.obj(
				"bool" -> Json.obj(
					"filter" -> Json.arr(
						Json.obj("exists" -> Json.obj("field" -> config.getString("DATE_FIELD").get))
					)
				)
			),
			"aggregations" -> Json.obj(
				"date_aggregation" -> Json.obj(
					"date_histogram" -> Json.obj(
						"field" -> config.getString("DATE_FIELD").get,
						"interval" -> "year",
						"format" -> "yyyy",
						"min_doc_count" -> 0,
						"extended_bounds" -> Json.obj(
							"min" -> startDate.getMillis(),
							"max" -> endDate.getMillis()
						)
					),
					"aggregations" -> Json.obj(
						aggCardWork -> Json.obj(
							"cardinality" -> Json.obj(
								"field" -> config.getString("WORK_FIELD").get,
								"precision_threshold" -> config.getInt("WORK_FIELD_PRECISION").get
							)
						),
						aggCardAuth -> Json.obj(
							"cardinality" -> Json.obj(
								"field" -> config.getString("AUTH_FIELD").get,
								"precision_threshold" -> config.getInt("AUTH_FIELD_PRECISION").get
							)
						),
						aggTermFreq -> Json.obj(
							"sum" -> Json.obj(
								"field" -> termFreqField
							)
						)
					)
				)
			)
		)

		ws.url(config.getString("ES_HOST").get + "/" + config.getString("ES_INDEX").get + "/" + config.getString("ES_TYPE").get + "/_search")
			.post(queryData)
			.map { response =>
				if (response.status == 200) {

					var parsedResponseData: JsValue = Json.obj()
					val failedCount: Int = (response.json \ "_shards" \ "failed").as[Int]
					if (failedCount > 0) {
						parsedResponseData = Json.obj("message" -> "Elasticsearch query failed.")
					} else {

						var buckets: List[JsObject] = (response.json \ "aggregations" \ "date_aggregation" \ "buckets").as[List[JsObject]]
						var parsedBuckets: ListBuffer[JsObject] = ListBuffer()
						for (b <- buckets) {
							parsedBuckets += Json.obj(
								"key" -> (b \ "key_as_string").as[String],
								"doc_count" -> (b \ "doc_count").as[Int],
								"auth_count" -> (b \ (config.getString("AUTH_FIELD_NAME").get) \ "value").as[Int],
								"work_count" -> (b \ (config.getString("WORK_FIELD_NAME").get) \ "value").as[Int],
								"term_freq" -> (b \ (config.getString("TERM_FREQ_FIELD_NAME").get) \ "value").as[Int]
							)
						}

						parsedResponseData = Json.obj(
							"data" -> Json.obj(
								"total_hits" -> (response.json \ "hits" \ "total").as[Int],
								"buckets" -> parsedBuckets
							)
						)
					}
					Ok(parsedResponseData)
				} else {
					InternalServerError(response.body)
				}
			}
			.recover {
				case e: Throwable => BadRequest("Bad request!")
			}

	}

	def getTimelineAggs(searchQuery: String, queryMode: String, queryTranslated: Boolean) = Action.async {

		// Check query mode
		val parsedQueryMode = checkQueryMode(queryMode)

		// Parse search params
		var queryObjectList = queryParser(searchQuery)

		// Analyze search queries.
		var futureAnalyzeList: ListBuffer[Future[QueryTerm]] = ListBuffer()
		for (qbl <- queryObjectList) {
			for (qt <- qbl.queryTerms) {
				futureAnalyzeList += queryAnalyzer(qt)
			}
		}
		
		// When analyzed search queries are ready.
		Future.sequence(futureAnalyzeList).flatMap { analyzedQueryTerms => 
			
			// Attach analyzed queries
			queryObjectList = attachAnalyzed(analyzedQueryTerms, queryObjectList)

			// Create and make query
			var futureFinalList: ListBuffer[Future[(JsObject,QueryObject)]] = ListBuffer()
			for (qbl <- queryObjectList) {

				var queryData: JsObject = createTimelineQuery(qbl, parsedQueryMode, queryTranslated)
				//print('\n')
				//println(parsedQueryMode)
				//println(Json.prettyPrint(queryData))

				futureFinalList += ws.url(config.getString("ES_HOST").get + "/" + config.getString("ES_INDEX").get + "/_search")
					.post(queryData)
					.map { response =>
						(response.json.as[JsObject], qbl)
					}
			}

			// Parse response and send back to client
			Future.sequence(futureFinalList).flatMap { responseDataList =>

				scala.concurrent.Future {
					Ok(createTimelineResponse(responseDataList, parsedQueryMode))
				}
			}
			
		}	
		
	}

	def getBarchart(searchQuery: String, startDate: String, endDate: String, queryMode: String, queryTranslated: Boolean, aggField: String) = Action.async {

		// Check query mode
		val parsedQueryMode = checkQueryMode(queryMode)

		// Check agg field
		val parsedAggField = checkAggField(aggField)

		// Parse date span
		val dateSpan = dateParser(startDate, endDate)

		// Parse search params
		var queryObjectList = queryParser(searchQuery)

		// Analyze search queries.
		var futureAnalyzeList: ListBuffer[Future[QueryTerm]] = ListBuffer()
		for (qbl <- queryObjectList) {
			for (qt <- qbl.queryTerms) {
				futureAnalyzeList += queryAnalyzer(qt)
			}
		}

		// When analyzed search queries are ready.
		Future.sequence(futureAnalyzeList).flatMap { analyzedQueryTerms => 

			// Attach analyzed queries
			queryObjectList = attachAnalyzed(analyzedQueryTerms, queryObjectList)

			// Create and make query
			var futureFinalList: ListBuffer[Future[(JsObject,QueryObject)]] = ListBuffer()
			for (qbl <- queryObjectList) {
				var queryData: JsObject = createBarchartQuery(qbl, dateSpan, parsedQueryMode, queryTranslated, parsedAggField)
				//print('\n')
				//println(parsedQueryMode)
				//println(Json.prettyPrint(queryData))

				futureFinalList += ws.url(config.getString("ES_HOST").get + "/" + config.getString("ES_INDEX").get + "/_search")
					.post(queryData)
					.map { response =>
						(response.json.as[JsObject], qbl)
					}
			}

			// Parse response and send back to client
			Future.sequence(futureFinalList).flatMap { responseDataList =>

				scala.concurrent.Future {
					Ok(createBarchartResponse(responseDataList, dateSpan, parsedQueryMode, aggField))
				}
			}
		}
		
	}

	def getHitList(searchQuery: String, startDate: String, endDate: String, fromIndex: Int, queryMode: String, queryTranslated: Boolean, sortField: String, sortOrder: String) = Action.async {

		// Check query mode
		val parsedQueryMode = checkQueryMode(queryMode)

		// Check sort mode
		val parsedSortField = checkSortField(sortField)
		val parsedSortOrder = checkSortOrder(sortOrder)

		// Parse date span
		val dateSpan = dateParser(startDate, endDate)

		// Parse search params
		var queryObjectList = queryParser(searchQuery)

		// Analyze search queries.
		var futureAnalyzeList: ListBuffer[Future[QueryTerm]] = ListBuffer()
		for (qbl <- queryObjectList) {
			for (qt <- qbl.queryTerms) {
				futureAnalyzeList += queryAnalyzer(qt)
			}
		}

		// When analyzed search queries are ready.
		Future.sequence(futureAnalyzeList).flatMap { analyzedQueryTerms => 

			// Attach analyzed queries
			queryObjectList = attachAnalyzed(analyzedQueryTerms, queryObjectList)

			// Create and make query
			var futureFinalList: ListBuffer[Future[(JsObject,QueryObject)]] = ListBuffer()
			for (qbl <- queryObjectList) {
				var queryData: JsObject = createHitListQuery(qbl, dateSpan, fromIndex, parsedQueryMode, queryTranslated, parsedSortField, parsedSortOrder)
				//print('\n')
				//println(parsedQueryMode)
				//println(Json.prettyPrint(queryData))

				futureFinalList += ws.url(config.getString("ES_HOST").get + "/" + config.getString("ES_INDEX").get + "/_search")
					.post(queryData)
					.map { response =>
						(response.json.as[JsObject], qbl)
					}
			}

			// Parse response and send back to client
			Future.sequence(futureFinalList).flatMap { responseDataList =>

				scala.concurrent.Future {
					Ok(createHitlistResponse(responseDataList, dateSpan, fromIndex, parsedQueryMode))
				}
			}
		}

	}

	/*
	 * Query functions
	 */
	def createHitListQuery(queryObject: QueryObject, dateSpan: DateSpan, fromIndex: Int, queryMode: String, queryTranslated: Boolean, sortField: String, sortOrder: String) : JsObject = {

		// Create query object
		var queryFilter: JsObject = Json.obj(
			"bool" -> Json.obj(
				"must" -> getBoolMustQuery(queryObject, queryMode, queryTranslated),
				"filter" -> (getBoolFilterExists() ++ getBoolFilterTerms(queryObject) ++ getBoolFilterDates(dateSpan))
			)
		)

		// Create sort object
		var sortObject: JsArray = Json.arr()
		if (sortOrder != "") {
			sortObject = Json.arr(
				Json.obj(
					sortField -> Json.obj(
						"order" -> sortOrder
					)
				),
				"page_idx"
			)
		} else {
			sortObject = Json.arr(
				sortField,
				"page_idx"
			)
		}

		return Json.obj(
			"from" -> fromIndex,
			"size" -> config.getInt("HIT_RETURN_COUNT").get,
			"query" -> queryFilter,
			"highlight" -> Json.obj(
				"pre_tags" -> Json.arr("<span class='highlight'>"),
				"post_tags" -> Json.arr("</span>"),
				"number_of_fragments" -> 0,
				"fields" -> Json.obj(
					config.getString("TEXT_FIELD_ORIGINAL").get -> Json.obj(),
					config.getString("TEXT_FIELD_TRANSLATED").get -> Json.obj()
				)
			),
			"sort" -> sortObject
		)

	}

	def createBarchartQuery(queryObject: QueryObject, dateSpan: DateSpan, queryMode: String, queryTranslated: Boolean, aggField: String) : JsObject = {

		// Create query object
		var queryFilter: JsObject = Json.obj(
			"bool" -> Json.obj(
				"must" -> getBoolMustQuery(queryObject, queryMode, queryTranslated),
				"filter" -> (getBoolFilterExists() ++ getBoolFilterTerms(queryObject) ++ getBoolFilterDates(dateSpan))
			)
		)

		var termsAggregation: JsObject = Json.obj(
			"terms_aggregation" -> Json.obj(
				"terms" -> Json.obj(
					"field" -> aggField,
					"size" -> config.getInt("TERM_AGG_SIZE").get,
					"missing" -> config.getString("TERM_AGG_MISSING").get
				)
			)
		)

		return Json.obj(
			"size" -> 0,
			"query" -> queryFilter,
			"aggregations" -> termsAggregation
		)

	}

	def createTimelineQuery(queryObject: QueryObject, queryMode: String, queryTranslated: Boolean) : JsObject = {

		// Create query object
		var queryFilter: JsObject = Json.obj(
			"bool" -> Json.obj(
				"must" -> getBoolMustQuery(queryObject, queryMode, queryTranslated),
				"filter" -> (getBoolFilterExists() ++ getBoolFilterTerms(queryObject))
			)
		)

		// Create date object for aggs
		var startDate = new DateTime(config.getString("TL_START_DATE").get)
		var endDate = new DateTime(config.getString("TL_END_DATE").get)

		var dateAggregation: JsObject = Json.obj(
			"field" -> config.getString("DATE_FIELD").get,
			"interval" -> "year",
			"format" -> "yyyy",
			"min_doc_count" -> 0,
			"extended_bounds" -> Json.obj(
				"min" -> startDate.getMillis(),
				"max" -> endDate.getMillis()
			)
		)

		// Create term frequency script
		var termFreqField: String = config.getString("TEXT_FIELD_ORIGINAL").get
		if (queryTranslated) {
			termFreqField = config.getString("TEXT_FIELD_TRANSLATED").get
		}

		// If search string is empty.
		var aggTermFreqObj: JsObject = Json.obj(
			"sum" -> Json.obj(
				"field" -> JsString(termFreqField + ".length")
			)
		)

		if (queryObject.queryTerms(0).originalTerm != "") {
			var scriptQuery: String = ""

			if (queryObject.queryTerms.length > 1 && queryMode == config.getString("QM_EXACT").get) {
				scriptQuery = "_index['" + termFreqField + ".shingles']['" + queryObject.queryTerms.map(_.analyzedTerm).mkString(" ") + "'].tf()"
			} else {
				for (qt <- queryObject.queryTerms) {
					scriptQuery += "_index['" + termFreqField + "']['" + qt.analyzedTerm + "'].tf() +"
				}
				scriptQuery = scriptQuery.dropRight(2)
			}
			
			aggTermFreqObj = Json.obj(
				"sum" -> Json.obj(
					"script" -> scriptQuery
				)
			)
		}

		// Create aggs
		var aggCardWork: String = config.getString("WORK_FIELD_NAME").get
		var aggCardAuth: String = config.getString("AUTH_FIELD_NAME").get
		var aggTermFreq: String = config.getString("TERM_FREQ_FIELD_NAME").get
		var aggregation: JsObject = Json.obj(
			"date_aggregation" -> Json.obj(
				"date_histogram" -> dateAggregation,
				"aggregations" -> Json.obj(
					aggCardWork -> Json.obj(
						"cardinality" -> Json.obj(
							"field" -> config.getString("WORK_FIELD").get,
							"precision_threshold" -> config.getInt("WORK_FIELD_PRECISION").get
						)
					),
					aggCardAuth -> Json.obj(
						"cardinality" -> Json.obj(
							"field" -> config.getString("AUTH_FIELD").get,
							"precision_threshold" -> config.getInt("AUTH_FIELD_PRECISION").get
						)
					),
					aggTermFreq -> aggTermFreqObj
				)
			)
		)

		return Json.obj(
			"size" -> 0,
			"query" -> queryFilter,
			"aggregations" -> aggregation
		)

	} 

	/*
	 * Parse response functions
	 */

	def createTimelineResponse(responseDataList: ListBuffer[(JsObject,QueryObject)], queryMode: String): JsValue = {

		var responseList: ListBuffer[JsObject] = ListBuffer()

		for (rdl <- responseDataList) {

			// Attach query object
			var filterList: ListBuffer[JsObject] = rdl._2.queryFilters.map(x => 
				Json.obj(
					"key" -> x.key,
					"terms" -> Json.toJson(x.terms)
				)
			)
			var queryObject: JsObject = Json.obj(
				"id" -> rdl._2.idSeq,
				"mode" -> queryMode,
				"original_search_terms" -> rdl._2.queryTerms.map(_.originalTerm).mkString(" "),
				"analyzed_search_terms" -> rdl._2.queryTerms.map(_.analyzedTerm).mkString(" "),
				"filters" -> Json.toJson(filterList)
			)

			// Check if shard failed
			var parsedResponseData: JsValue = Json.obj()
			if (rdl._1.keys.contains("error")) {
				parsedResponseData = rdl._1
			} else {
				val failedCount: Int = (rdl._1 \ "_shards" \ "failed").as[Int]
				if (failedCount > 0) {
					parsedResponseData = Json.obj("message" -> "Elasticsearch query failed.")
				} else {
					var buckets: List[JsObject] = (rdl._1 \ "aggregations" \ "date_aggregation" \ "buckets").as[List[JsObject]]
					var parsedBuckets: ListBuffer[JsObject] = ListBuffer()
					for (b <- buckets) {
						parsedBuckets += Json.obj(
							"key" -> (b \ "key_as_string").as[String],
							"doc_count" -> (b \ "doc_count").as[Int],
							"auth_count" -> (b \ (config.getString("AUTH_FIELD_NAME").get) \ "value").as[Int],
							"work_count" -> (b \ (config.getString("WORK_FIELD_NAME").get) \ "value").as[Int],
							"term_freq" -> (b \ (config.getString("TERM_FREQ_FIELD_NAME").get) \ "value").as[Int]
						)
					}

					parsedResponseData = Json.obj(
						"es_query_time" -> (rdl._1 \ "took").as[JsNumber],
						"total_hits" -> (rdl._1 \ "hits" \ "total").as[Int],
						"buckets" -> Json.toJson(parsedBuckets)
					)
				}
			}

			responseList += Json.obj(
				"query" -> queryObject,
				"data" -> parsedResponseData
			)

		}

		return Json.toJson(responseList)

	}

	def createBarchartResponse(responseDataList: ListBuffer[(JsObject,QueryObject)], dateSpan: DateSpan, queryMode: String, aggField: String): JsValue = {

		var responseList: ListBuffer[JsObject] = ListBuffer()

		for (rdl <- responseDataList) {

			// Attach query object
			var filterList: ListBuffer[JsObject] = rdl._2.queryFilters.map(x => 
				Json.obj(
					"key" -> x.key,
					"terms" -> Json.toJson(x.terms)
				)
			)
			var queryObject: JsObject = Json.obj(
				"id" -> rdl._2.idSeq,
				"mode" -> queryMode,
				"agg_field" -> aggField,
				"original_search_terms" -> rdl._2.queryTerms.map(_.originalTerm).mkString(" "),
				"analyzed_search_terms" -> rdl._2.queryTerms.map(_.analyzedTerm).mkString(" "),
				"filters" -> Json.toJson(filterList),
				"datespan" -> Json.obj(
					"start_date" -> DateTimeFormat.forPattern("yyyy-MM-dd").print(dateSpan.startDate),
					"end_date" -> DateTimeFormat.forPattern("yyyy-MM-dd").print(dateSpan.endDate.getMillis())
				)
			)

			// Check if shard failed
			var parsedResponseData: JsValue = Json.obj()
			if (rdl._1.keys.contains("error")) {
				parsedResponseData = rdl._1
			} else {
				val failedCount: Int = (rdl._1 \ "_shards" \ "failed").as[Int]
				if (failedCount > 0) {
					parsedResponseData = Json.obj("message" -> "Elasticsearch query failed.")
				} else {
					var buckets: List[JsObject] = (rdl._1 \ "aggregations" \ "terms_aggregation" \ "buckets").as[List[JsObject]]
					var parsedBuckets: ListBuffer[JsObject] = ListBuffer()
					for (b <- buckets) {
						parsedBuckets += Json.obj(
							"key" -> (b \ "key").as[String],
							"doc_count" -> (b \ "doc_count").as[Int]
						)
					}

					parsedResponseData = Json.obj(
						"es_query_time" -> (rdl._1 \ "took").as[JsNumber],
						"total_hits" -> (rdl._1 \ "hits" \ "total").as[Int],
						"sum_other_doc_count" -> (rdl._1 \ "aggregations" \ "terms_aggregation" \ "sum_other_doc_count").as[Int],
						"buckets" -> Json.toJson(parsedBuckets)
					)
				}
			}

			responseList += Json.obj(
				"query" -> queryObject,
				"data" -> parsedResponseData
			)

		}

		return Json.toJson(responseList)

	}

	def createHitlistResponse(responseDataList: ListBuffer[(JsObject,QueryObject)], dateSpan: DateSpan, fromIndex: Int, queryMode: String): JsValue = {

		var responseList: ListBuffer[JsObject] = ListBuffer()

		for (rdl <- responseDataList) {

			// Attach query object
			var filterList: ListBuffer[JsObject] = rdl._2.queryFilters.map(x => 
				Json.obj(
					"key" -> x.key,
					"terms" -> Json.toJson(x.terms)
				)
			)
			var queryObject: JsObject = Json.obj(
				"id" -> rdl._2.idSeq,
				"mode" -> queryMode,
				"from_index" -> fromIndex,
				"original_search_terms" -> rdl._2.queryTerms.map(_.originalTerm).mkString(" "),
				"analyzed_search_terms" -> rdl._2.queryTerms.map(_.analyzedTerm).mkString(" "),
				"filters" -> Json.toJson(filterList),
				"datespan" -> Json.obj(
					"start_date" -> DateTimeFormat.forPattern("yyyy-MM-dd").print(dateSpan.startDate),
					"end_date" -> DateTimeFormat.forPattern("yyyy-MM-dd").print(dateSpan.endDate.getMillis())
				)
			)

			// Check if shard failed
			var parsedResponseData: JsValue = Json.obj()
			if (rdl._1.keys.contains("error")) {
				parsedResponseData = rdl._1
			} else {
				val failedCount: Int = (rdl._1 \ "_shards" \ "failed").as[Int]
				if (failedCount > 0) {
					parsedResponseData = Json.obj("message" -> "Elasticsearch query failed.")
				} else {
					parsedResponseData = Json.obj(
						"es_query_time" -> (rdl._1 \ "took").as[JsNumber],
						"total_hits" -> (rdl._1 \ "hits" \ "total").as[Int],
						"hits" ->  Json.toJson((rdl._1 \ "hits" \ "hits").as[List[JsObject]])
					)
				}
			}

			responseList += Json.obj(
				"query" -> queryObject,
				"data" -> parsedResponseData
			)

		}

		return Json.toJson(responseList)

	}

	/*
	 *	Get functions
	 */

	def getBoolMustQuery(queryObject: QueryObject, queryMode: String, queryTranslated: Boolean) : JsArray = {

		// Check which text field to use.
		var textField: String = config.getString("TEXT_FIELD_ORIGINAL").get
		if (queryTranslated) {
			textField = config.getString("TEXT_FIELD_TRANSLATED").get
		}

		// Create object for search terms
		var boolMustQuery: JsArray = Json.arr()
		
		if (queryObject.queryTerms(0).originalTerm == "") {
			boolMustQuery = Json.arr(
				Json.obj("match_all" -> Json.obj())
			)
		} else {

			if (queryMode == config.getString("QM_ANY").get || queryObject.queryTerms.length < 2) { // If query mode ANYWHERE or only one search term

				boolMustQuery = Json.toJson(queryObject.queryTerms.map(qt => 
					Json.obj(
						"match" -> Json.obj(
							textField -> qt.originalTerm
						)
					)
				)).as[JsArray]

			} else if (queryMode == config.getString("QM_EXACT").get) { // If query mode EXACT

				var exactPhrase: String = queryObject.queryTerms.map(_.originalTerm).mkString(" ")

				boolMustQuery = Json.arr(
					Json.obj(
						"match_phrase" -> Json.obj(
							textField -> exactPhrase
						)
					)
				)

			} else { // If query mode SPAN NEAR

				var ordinal: Boolean = if (queryMode == config.getString("QM_SPAN_NEAR").get) false else true

				var spanTerms: ListBuffer[JsObject] = ListBuffer()
				for (qt <- queryObject.queryTerms) {
					spanTerms += Json.obj("span_term" -> Json.obj(textField -> qt.analyzedTerm))
				}

				boolMustQuery = Json.arr(
					Json.obj(
						"span_near" -> Json.obj(
							"clauses" -> Json.toJson(spanTerms),
							"slop" -> config.getInt("SPAN_SLOP_COUNT").get,
							"in_order" -> ordinal
						)
					)
				)
			}
		}

		// Add filter for authors
		var specialAuthorFilter: JsArray = Json.toJson(
			queryObject.queryFilters.filter(fl =>
				if (config.getStringList("FILTER_MAPPING_SPECIAL").get.contains(fl.key)) {
					true
				} else {
					false
				}
			).map(fl =>
				Json.obj(
					"match" -> Json.obj(
						config.getString("FILTER_MAPPING." + fl.key).get -> fl.terms.mkString(" ")
					)
				)
			)
		).as[JsArray]
		
		return boolMustQuery ++ specialAuthorFilter

	}

	def getBoolFilterExists() : JsArray = {

		// Create object for filter terms
		return Json.arr(
			Json.obj("exists" -> Json.obj("field" -> config.getString("DATE_FIELD").get))
		)

	}

	def getBoolFilterTerms(queryObject: QueryObject) : JsArray = {

		return Json.toJson(
			queryObject.queryFilters.filter(fl =>
				if (config.getStringList("FILTER_MAPPING_SPECIAL").get.contains(fl.key)) {
					false
				} else {
					true
				}
			).map(fl =>
				if(config.getString("FILTER_MAPPING." + fl.key) == None) {
					Json.obj(
						"terms" -> Json.obj(
							fl.key -> Json.toJson(fl.terms)
						)
					)
				}
				else {
					Json.obj(
						"terms" -> Json.obj(
							config.getString("FILTER_MAPPING." + fl.key).get -> Json.toJson(fl.terms)
						)
					)
				}
			)
		).as[JsArray]

	}

	def getBoolFilterDates(dateSpan: DateSpan) : JsArray = {

		return Json.arr(
			Json.obj(
				"range" -> Json.obj(
					config.getString("DATE_FIELD").get -> Json.obj(
						"gte" -> dateSpan.startDate.getYear(),
						"lte" -> dateSpan.endDate.getYear(),
						"format" -> "yyyy"
					)
				)
			)
		)

	}

	/*
	 *	Help functions
	 */

	// Fetch analyzed string
	def queryAnalyzer(queryTerm: QueryTerm) : Future[QueryTerm] = {

		// If terms is going to be analyzed.
		if (config.getBoolean("ANALYZE_TERMS").get) {

			var analyzerType = "custom_html_analyzer"
			var queryData = Json.obj(
				"analyzer" -> analyzerType,
				"text" -> queryTerm.originalTerm
			)

			val futureResult: Future[QueryTerm] = ws.url(config.getString("ES_HOST").get + "/" + config.getString("ES_INDEX").get + "/_analyze")
				.post(queryData)
				.map { response =>
					if (response.status == 200) {
						if ((response.json \ "tokens").as[List[JsObject]].length < 1) {
							QueryTerm(queryTerm.originalTerm, "")
						} else {
							QueryTerm(queryTerm.originalTerm, (response.json \ "tokens" \\ "token")(0).as[String])
						}
						
					} else {
						QueryTerm(queryTerm.originalTerm, "")
					}
				}
		
			futureResult

		} else {

			val futureResult: Future[QueryTerm] = scala.concurrent.Future {
				QueryTerm(queryTerm.originalTerm, queryTerm.originalTerm)
			}

			futureResult

		}

	}

	// Attach new analyzed queries to query object list
	def attachAnalyzed(analyzedQueryTerms: ListBuffer[QueryTerm], queryObjectList: ListBuffer[QueryObject]) : ListBuffer[QueryObject] = {

		// Convert query term list to map
		var queryTermMap: Map[String, String] = Map()
		for (aqt <- analyzedQueryTerms) {
			queryTermMap += (aqt.originalTerm -> aqt.analyzedTerm)
		}

		// Insert new analyzed terms in query object
		var newQueryObjectList: ListBuffer[QueryObject] = ListBuffer()
		for (qbl <- queryObjectList) {
			var queryTerms: ListBuffer[QueryTerm] = ListBuffer()
			for (qt <- qbl.queryTerms) {
				queryTerms += qt.copy(analyzedTerm = queryTermMap(qt.originalTerm))
			}
			newQueryObjectList += qbl.copy(queryTerms = queryTerms)
		}

		return newQueryObjectList

	}

	// Parser for search queries
	def queryParser(searchQuery: String) : ListBuffer[QueryObject] = {

		// Declare return object
		var queryObjectList: ListBuffer[QueryObject] = ListBuffer()

		// Split search terms
		var splittedQuery: ListBuffer[String] = ListBuffer()
		var startIndex: Int = 0
		var currIndex: Int = 0
		var inParentheses: Boolean = false
		for (c <- searchQuery) {

			if (c == '(') inParentheses = true
			else if (c == ')') inParentheses = false

			if (currIndex == (searchQuery.length() - 1)) {
				splittedQuery += searchQuery.substring(startIndex).trim()
			} else if (c == ',' && !inParentheses) {
				splittedQuery += searchQuery.substring(startIndex, currIndex).trim()
				startIndex = currIndex + 1
			}

			currIndex += 1
		}

		// Parse splitted terms
		if (splittedQuery.isEmpty) {
			queryObjectList += QueryObject(randomAlphanumericString(10), ListBuffer(QueryTerm("", "")), ListBuffer())
		} else {

			for (sq <- splittedQuery) {

				var queryTerms: ListBuffer[String] = ListBuffer()
				var queryFilters: ListBuffer[QueryFilter] = ListBuffer()

				var pattern = "(\\S*?):\\(.*?\\)".r
				val filterExtractions = pattern.findAllIn(sq)
				val terms = pattern.replaceAllIn(sq, "")

				var wordCount: Int = terms.split(" ").length

				// Parse search terms
				if (terms == "") {
					queryTerms += ""
				} else if (terms.contains('(') && terms.contains(')')) {
					for (t <- terms.replace('(', ' ').replace(')', ' ').split(",").toList) {
						queryTerms += t.trim()
					}
				} else {
					queryTerms += terms.trim()
				}

				// Parse filters
				for(fe <- filterExtractions) {

					var filterKey: Option[String] = "(.*?)(?=:)".r.findFirstIn(fe.trim)
					var filterParams: Option[String] = "(?<=\\()(.*?)(?=\\))".r.findFirstIn(fe.trim)

					// Check if filter key and params are found
					if (filterKey != None && filterParams != None) {
						queryFilters += QueryFilter(filterKey.get, filterParams.get.split(",").map(_.trim).toList)
					}

				}

				// Split parse term sequences and insert parsed data into query object list
				for (qt <- queryTerms) {
					var queryTerms: ListBuffer[QueryTerm] = ListBuffer()
					for (t <- qt.split(" ").map(_.trim).toList) {
						queryTerms += QueryTerm(t, "")
					}
					
					queryObjectList += QueryObject(randomAlphanumericString(10), queryTerms, queryFilters)
				}

			}
		}

		return queryObjectList

	}

	// Check query mode
	def checkQueryMode(queryMode: String) : String = {

		val availableQueryModes: List[String] = List(
			config.getString("QM_EXACT").get,
			config.getString("QM_SPAN_NEAR").get,
			config.getString("QM_SPAN_NEAR_ORD").get,
			config.getString("QM_ANY").get
		)

		if (availableQueryModes.contains(queryMode)) {
			return queryMode
		} else {
			return config.getString("QM_EXACT").get
		}

	}

	// Generate a random string of length n from the given alphabet
	def randomString(alphabet: String)(n: Int): String = {

		val random = new Random()
		return Stream.continually(random.nextInt(alphabet.size)).map(alphabet).take(n).mkString

	}
		
	// Generate a random alphabnumeric string of length n
	def randomAlphanumericString(n: Int): String = {

		return randomString("abcdefghijklmnopqrstuvwxyz0123456789")(n)

	}

	/*
	 *	Check functions
	 */

	// Check agg field
	def checkAggField(aggField: String) : String = {

		if (config.getString("AGG_FIELDS." + aggField) == None) {
			return config.getString("AGG_FIELDS.authors").get
		} else {
			return config.getString("AGG_FIELDS." + aggField).get
		}

	}

	// Check sort field
	def checkSortField(sortField: String) : String = {

		if (config.getString("SORT_MODE." + sortField) == None) {
			return config.getString("SORT_MODE.score").get
		} else {
			return config.getString("SORT_MODE." + sortField).get
		}

	}

	// Check sort order
	def checkSortOrder(sortOrder: String) : String = {

		if (sortOrder != "asc" && sortOrder != "desc") {
			return ""
		} else {
			return sortOrder
		}

	}

	// Parse start and end date.
	def dateParser(startDate: String, endDate: String) : DateSpan = {

		var dateParsers: Array[DateTimeParser] = Array(
			DateTimeFormat.forPattern("yyyyMMdd").getParser(),
			DateTimeFormat.forPattern("yy").getParser(),
			DateTimeFormat.forPattern("yyyy").getParser()
		)

		var dateFormatter: DateTimeFormatter = new DateTimeFormatterBuilder().append(null, dateParsers).toFormatter();
		var startDateObject: DateTime = dateFormatter.parseDateTime(startDate)
		var endDateObject: DateTime = dateFormatter.parseDateTime(endDate).withMonthOfYear(12).withDayOfMonth(31)

		return DateSpan(startDateObject, endDateObject)

	}

}