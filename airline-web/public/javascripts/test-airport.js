var map
var flightPaths
var activeInput
var markers

$( document ).ready(function() {
	flightPaths = []
	markers = []
	activeInput = $("#fromAirport")
	loadAirlines()
})

function initMap() {
  map = L.map('map', {
	center: [20, 150.644],
	zoom: 2,
	minZoom: 2,
	worldCopyJump: true
  })
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map)
  
  getAirports()
  refreshLinks()
}

function addMarkers(airports) {
	for (i = 0; i < airports.length; i++) {
		  var airportInfo = airports[i]
		  var position = [airportInfo.latitude, airportInfo.longitude]
		  var marker = L.marker(position, { title: airportInfo.name }).addTo(map)
		  marker.airportCode = airportInfo.iata
		  marker.airportId = airportInfo.id
		  
		  marker.on('click', function() {
			  var airportId = this.airportId
			  if (activeInput.is($("#fromAirport"))) {
				  $("#fromAirport").val(airportId)
				  activeInput = $("#toAirport")
			  } else {
				  $("#toAirport").val(airportId)
				  activeInput = $("#fromAirport")
			  }
		  });
		  markers.push(marker)
	}
}

function loadAirlines() {
	$.ajax({
		type: 'GET',
		url: "airlines",
	    contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(airlines) {
	    	$.each(airlines, function( key, airline ) {
	    		$("#airlineOption").append($("<option></option>").attr("value", airline.id).text(airline.name)); 
	  		});
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}

function loadConsumptions() {
	$("#consumptions").empty()
	$.ajax({
		type: 'GET',
		url: "link-consumptions",
	    contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(consumptions) {
	    	$.each(consumptions, function( key, consumption ) {
	    		$("#consumptions").append($("<div></div>").text(consumption.airlineName + " - " + consumption.fromAirportCode + "=>" + consumption.toAirportCode + " : " + consumption.consumption)); 
	  		});
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}

function insertTestLink() {
	if ($("#fromAirport").val() && $("#toAirport").val()) {
		var url = "test-links"
		var airportData = { 
			"fromAirportId" : parseInt($("#fromAirport").val()), 
			"toAirportId" : parseInt($("#toAirport").val()),
			"airlineId" : parseInt($("#airlineOption").val()),
			"capacity" : parseInt($("#capacity").val()), 
			"quality" : parseInt($("#quality").val()),
			"price" : parseFloat($("#price").val()) }
		$.ajax({
			type: 'PUT',
			url: url,
		    data: JSON.stringify(airportData),
		    contentType: 'application/json; charset=utf-8',
		    dataType: 'json',
		    success: function(savedLink) {
		        drawFlightPath(savedLink)
		    },
	        error: function(jqXHR, textStatus, errorThrown) {
		            console.log(JSON.stringify(jqXHR));
		            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
		    }
		});
	}
}

function removeAllLinks() {
	$.ajax({
		type: 'DELETE',
		url: "links",
	    success: function() {
	    	refreshLinks()
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
	
	
}

function refreshLinks() {
	//remove all links from UI first
	$.each(flightPaths, function( key, value ) {
		  if (map && value) {
		  	map.removeLayer(value)
		  }
		});
	flightPaths = []
	
	$.ajax({
		type: 'GET',
		url: "links",
	    contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(links) {
	    	$.each(links, function( key, link ) {
	    		drawFlightPath(link)
	  		});
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}

function drawFlightPath(link) {
   var flightPath = L.polyline(
     [[link.fromLatitude, link.fromLongitude], [link.toLatitude, link.toLongitude]],
     {
       color: '#F2B022',
       opacity: 1.0,
       weight: 2
     }
   ).addTo(map)
   flightPaths.push(flightPath)
}

function appendConsole(message) {
	$('#console').append( message + '<br/>')
}

function getAirports() {
	$.getJSON( "airports?count=10", function( data ) {
		  addMarkers(data)
		});
}
	
	
	
	
	
	
