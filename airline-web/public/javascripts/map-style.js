var currentStyles
var currentTypes
var pathOpacityByStyle = {
    "dark": {
        highlight: "0.9",
        normal: "0.5"
    },
    "light": {
        highlight: "1.0",
        normal: "0.8"
    }
}

var mapStyleSources = {
    dark: {
        url: "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png",
        attribution: "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors &copy; <a href=\"https://carto.com/attributions\">CARTO</a>"
    },
    light: {
        url: "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png",
        attribution: "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors &copy; <a href=\"https://carto.com/attributions\">CARTO</a>"
    }
}

function initStyles() {
    console.log("onload cookie" + $.cookie('currentMapStyles'))
    if ($.cookie('currentMapStyles')) {
        currentStyles = $.cookie('currentMapStyles')
    } else {
        currentStyles = 'dark'
        $.cookie('currentMapStyles', currentStyles)
    }
    console.log("onload " + currentStyles)

    console.log("onload cookie" + $.cookie('currentMapTypes'))
    if ($.cookie('currentMapTypes')) {
        currentTypes = $.cookie('currentMapTypes')
    } else {
        currentTypes = 'roadmap'
        $.cookie('currentMapTypes', currentTypes)
    }
    console.log("onload " + currentTypes)
}

function getMapStyles() {
    console.log("getting " + currentStyles)
    return currentStyles
}

function getMapTypes() {
    console.log("getting " + currentTypes)
    return currentTypes
}

function applyMapStyle(mapInstance) {
    if (!mapInstance) {
        return
    }
    var style = getMapStyles()
    var source = mapStyleSources[style] || mapStyleSources.dark
    if (mapInstance._styleLayer) {
        mapInstance.removeLayer(mapInstance._styleLayer)
    }
    var layer = L.tileLayer(source.url, {
        attribution: source.attribution,
        maxZoom: 19
    })
    layer.addTo(mapInstance)
    mapInstance._styleLayer = layer
}

function toggleMapLight() {
    if (currentStyles == 'dark') {
        currentStyles = 'light'
    } else {
        currentStyles = 'dark'
    }
    $.cookie('currentMapStyles', currentStyles)
    console.log($.cookie('currentMapStyles'))

    applyMapStyle(map)
    refreshLinks(false)
}
