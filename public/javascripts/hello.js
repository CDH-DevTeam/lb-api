if (window.console) {

	console.log("Welcome to your Play application's JavaScript!");

	http_req = new XMLHttpRequest();

	http_req.onreadystatechange = function() {
		if (http_req.readyState === XMLHttpRequest.DONE) {
			if (http_req.status === 200) {
				var json_data = JSON.parse(http_req.responseText)
				console.log(json_data);
				//console.log(http_req.responseText);
				
				for (var j = 0; j < json_data.length; ++j) {
					console.log('Query Time: ' + json_data[j]['data']['es_query_time']);
					console.log('Total Hits: ' + json_data[j]['data']['total_hits']);
					console.log('');
				}
				
				/*
				for (var j = 0; j < json_data.length; ++j) {
					var su = 0;
					for (var i = 0; i < json_data[j]['data']['buckets'].length; ++i) {
						su += json_data[j]['data']['buckets'][i]['doc_count'];
					}
					console.log('Query mode: ' + json_data[j]['query']['mode']);
					console.log('Count: ' + su);
					console.log('Total hits: ' + json_data[j]['data']['total_hits']);
					console.log('');
				}
				*/
				
				console.log('\n\n');
				
			} else {
				console.log(http_req.responseText);
			}
		}
	}

	entry = 'stad'

	//window.open('http://0.0.0.0:9000/barchart?searchQuery=' + entry + '&startDate=1500&endDate=2017&queryMode=exact&aggField=författare','_blank');

	//entry = 'sverige författare:(August Strindberg)'


	//window.open('http://0.0.0.0:9000/hitlist?searchQuery=' + entry + '&startDate=1500&endDate=2017&fromIndex=0&queryMode=exact','_blank');


	//http_req.open('GET', 'http://0.0.0.0:9000/timeline/aggs?searchQuery=' + entry + '&queryMode=exact', false);
	//http_req.send();

	//http_req.open('GET', 'http://0.0.0.0:9000/barchart?searchQuery=' + entry + '&startDate=1600&endDate=2016&queryMode=exact&aggField=works', false);
	//http_req.send();

	//http_req.open('GET', 'http://0.0.0.0:9000/hitlist?searchQuery=' + entry + '&startDate=1954&endDate=1955&fromIndex=20&queryMode=exact&sortField=date&sortOrder=asc', false);
	//http_req.send();

	//http_req.open('GET', 'http://0.0.0.0:9000/timeline/total', false);
	//http_req.send();
	/*
	http_req.open('GET', 'http://0.0.0.0:9000/timeline/aggs?searchQuery=' + entry + '&queryMode=exact&queryTranslated=true', false);
	http_req.send();

	
	http_req.open('GET', 'http://0.0.0.0:9000/timeline/aggs?searchQuery=' + entry + '&queryMode=anywhere', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/timeline/aggs?searchQuery=' + entry + '&queryMode=spanNear', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/timeline/aggs?searchQuery=' + entry + '&queryMode=spanNearOrdinal', false);
	http_req.send();
	*/
	/*

	http_req.open('GET', 'http://0.0.0.0:9000/barchart?searchQuery=' + entry + '&startDate=1954&endDate=1955&queryMode=exact&aggField=authors', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/barchart?searchQuery=' + entry + '&startDate=1850&endDate=1950&queryMode=exact&aggField=works', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/barchart?searchQuery=' + entry + '&startDate=1850&endDate=1950&queryMode=exact&aggField=mediatype', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/barchart?searchQuery=' + entry + '&startDate=1850&endDate=1950&queryMode=exact&aggField=texttype', false);
	http_req.send();

	
	http_req.open('GET', 'http://0.0.0.0:9000/hitlist?searchQuery=' + entry + '&startDate=1850&endDate=1950&fromIndex=0&queryMode=exact', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/hitlist?searchQuery=' + entry + '&startDate=1850&endDate=1950&fromIndex=50&queryMode=anywhere', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/hitlist?searchQuery=' + entry + '&startDate=1850&endDate=1950&fromIndex=100&queryMode=spanNear', false);
	http_req.send();

	http_req.open('GET', 'http://0.0.0.0:9000/hitlist?searchQuery=' + entry + '&startDate=1850&endDate=1950&fromIndex=200&queryMode=spanNearOrdinal', false);
	http_req.send();
	*/

	//window.open('http://0.0.0.0:9000/timeline/total','_blank');

}
