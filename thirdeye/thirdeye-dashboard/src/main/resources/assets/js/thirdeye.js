/**
 * @return An object with named path components
 */
function parsePath(dashboardPath) {
    var tokens = dashboardPath.split("/")

    var type = tokens[1]

    if (type == 'dashboard') {
        return {
            collection: tokens[2],
            metricFunction: tokens[3],
            metricViewType: tokens[4],
            dimensionViewType: tokens[5],
            baselineMillis: tokens[6],
            currentMillis: tokens[7]
        }
    } else if (type == 'metric') {
        return {
            collection: tokens[2],
            metricFunction: tokens[3],
            metricViewType: tokens[4],
            baselineMillis: tokens[5],
            currentMillis: tokens[6]
        }
    } else if (type == 'dimension') {
        return {
            collection: tokens[2],
            metricFunction: tokens[3],
            dimensionViewType: tokens[4],
            baselineMillis: tokens[5],
            currentMillis: tokens[6]
        }
    } else {
        throw "Invalid path type: " + type
    }
}

function parseHashParameters(hashString) {
  var params = {}

  if (hashString) {
    if (hashString.charAt(0) == '#') {
      hashString = hashString.substring(1)
    }

    var keyValuePairs = hashString.split('&')

    $.each(keyValuePairs, function(i, pair) {
      var tokens = pair.split('=')
      var key = decodeURIComponent(tokens[0])
      var value = decodeURIComponent(tokens[1])
      params[key] = value
    })
  }

  return params
}

function encodeHashParameters(hashParameters) {
  var keyValuePairs = []

  $.each(hashParameters, function(key, value) {
    keyValuePairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(value))
  })

  return '#' + keyValuePairs.join('&')
}

function setHashParameter(hashString, key, value) {
  var params = parseHashParameters(hashString)
  params[key] = value
  return encodeHashParameters(params)
}

// TODO: This expects function names to be suffixed with {size}_{unit}, which may not always be the case
function parseMetricFunction(metricFunction) {
  var functions = []
  var collector = ""
  for (var i = 0; i < metricFunction.length; i++) {
    if (metricFunction.charAt(i) == "(") { // end of function name
      // parse function name
      var tokens = collector.split("_")
      var unit = tokens[tokens.length - 1]
      var size = parseInt(tokens[tokens.length - 2])
      var name = tokens.slice(0, tokens.length - 2).join("_")

      functions.push({
        name: name,
        size: size,
        unit: unit
      })

      collector = ""
    } else if (metricFunction.charAt(i) == ")") { // end of function args
      // do nothing
    } else {
      collector += metricFunction.charAt(i)
    }
  }

  // What's left in collector is now the metric args
  var metrics = collector.split(",")

  return {
    functions: functions,
    metrics: metrics
  }
}

function getDashboardPath(path) {
    return "/dashboard"
        + "/" + path.collection
        + "/" + path.metricFunction
        + "/" + path.metricViewType
        + "/" + path.dimensionViewType
        + "/" + path.baselineMillis
        + "/" + path.currentMillis
}

function getFlotViewType(metricViewType) {
    if (metricViewType == 'INTRA_DAY') {
        return 'TIME_SERIES_FULL'
    } else {
        return metricViewType
    }
}

/**
 * @return A pathname suitable for getting the time series from the parsed path
 */
function getFlotPath(path, options) {
    var path = "/flot"
        + "/" + getFlotViewType(path.metricViewType)
        + "/" + path.collection
        + "/" + path.metricFunction
        + "/" + path.baselineMillis
        + "/" + path.currentMillis

    if (options && options.windowMillis) {
        path += "/" + options.windowMillis
    }

    if (options && options.windowOffsetMillis) {
        path += "/" + options.windowOffsetMillis
    }

    return path
}

function parseDimensionValues(queryString) {
    var dimensionValues = {}

    if (queryString) {
        var query = queryString
        if (query.indexOf("?") >= 0) {
            query = query.substring(1)
        }

        var tokens = query.split("&")
        $.each(tokens, function(i, token) {
            var keyValue = token.split("=")
            var key = decodeURIComponent(keyValue[0])
            var value = decodeURIComponent(keyValue[1])
            dimensionValues[key] = value
        })
    }

    return dimensionValues
}

function encodeDimensionValues(dimensionValues) {
    var components = []

    $.each(dimensionValues, function(key, value) {
        var encodedKey = encodeURIComponent(key)
        var encodedValue = encodeURIComponent(value)
        components.push(encodedKey + "=" + encodedValue)
    })

    return "?" + components.join("&")
}

/**
 * @param container The jQuery object in which to put the time series
 * @param tooltip The jQuery object which should contain the hover information
 */
function renderTimeSeries(container, tooltip, options) {
    var path = parsePath(window.location.pathname)
    var url = getFlotPath(path, options)

    if (!options) {
        options = {}
    }

    if (window.location.search) {
        url += window.location.search
        if (options.dimension) {
            url += '&' + encodeURIComponent(options.dimension) + '=!'
        }
    } else if (options.dimension) {
        url += '?' + encodeURIComponent(options.dimension) + '=!'
    }

    container.css('width', container.width())
    container.css('height', '400px')
    tooltip.css('position', 'absolute')
    tooltip.css('display', 'none')

    var minTickSize = (path.currentMillis - path.baselineMillis) / 10

    var shouldShowLegend = options.legend == null ? true : options.legend

    $.get(url, function(data) {
        if (options.filter) {
            data = options.filter(data)
        }

        container.plot(data, {
            legend: {
                show: shouldShowLegend,
                position: "se"
            },
            grid: {
                clickable: true,
                hoverable: true
            },
            xaxis: {
                tickFormatter: function(millis) {
                    return moment.utc(millis).tz(jstz().timezone_name).format("YYYY-MM-DD HH:mm")
                },
                minTickSize: minTickSize
            }
        })

        container.bind("plothover", function(event, pos, item) {
            if (item) {
                var dateString = moment.utc(item.datapoint[0]).tz(jstz().timezone_name).format()
                var value = item.datapoint[1]
                tooltip.html(item.series.label + " = " + value + " @ " + dateString)
                       .css({ top: container.position().top + 25, left: container.position().left + 75 })
                       .fadeIn(100)
            } else {
                tooltip.hide()
            }
        })

        if (options.click) {
            container.bind("plotclick", options.click)
        }
    })
}

/**
 * @param rawData The container with raw data
 * @return an object with the raw data
 */
function extractHeatMapData(rawData) {
    var data = {}

    rawData.find('.dimension-view-heat-map').each(function(i, heatMap) {
        var heatMapObj = $(heatMap)
        var id = heatMapObj.attr('metric') + '-' + heatMapObj.attr('dimension')
        data[id] = []

        // Get stats name mapping
        var statsNamesMapping = {}
        var statsNames = JSON.parse(heatMapObj.attr('stats-names'))
        $.each(statsNames, function(i, statsName) {
            statsNamesMapping[statsName] = i
        })

        heatMapObj.find('.dimension-view-heat-map-cell').each(function(j, cell) {
            var cellObj = $(cell)

            // Get cell stats
            var statsList = JSON.parse(cellObj.attr('stats'))
            var cellStats = {}
            $.each(statsNamesMapping, function(name, idx) {
                cellStats[name] = statsList[idx]
            })

            data[id].push({
                value: cellObj.attr('value'),
                stats: cellStats
            })
        })
    })

    return data
}

/**
 * @param rawData The raw heat map data (XML)
 * @param container The container in which to place the rendered heat map
 * @param options (sortKey, alphaKey, mainDisplayKey, positiveClass, negativeClass)
 */
function renderHeatMap(rawData, container, options) {
    var data = extractHeatMapData(rawData)

    container.empty()

    $.each(data, function(heatMapId, cells) {
        // Table structure
        var table = $("<table></table>", {
            class: 'uk-table dimension-view-heat-map-rendered'
        })
        table.append($("<caption></caption>", {
            html: heatMapId.replace('-', '.')
        }))

        // Sort cells
        cells.sort(options.comparator)

        // Group into rows
        var numColumns = 5
        var rows = []
        var currentRow = []
        for (var i = 0; i < cells.length; i++) {
            if (options.filter != null && !options.filter(cells[i])) {
                continue
            }
            currentRow.push(cells[i])
            if (currentRow.length == numColumns) {
                rows.push(currentRow)
                currentRow = []
            }
        }
        if (currentRow.length > 0) {
            rows.push(currentRow)
        }

        // Generate table body
        var tbody = $("<tbody></tbody>")
        $.each(rows, function(i, row) {
            var tr = $("<tr></tr>")
            $.each(row, function(j, cell) {
                var td = $("<td></td>")
                td.html(options.display(cell))
                td.css('background-color', options.backgroundColor(cell))
                td.hover(function() { $(this).css('cursor', 'pointer') })
                tr.append(td)

                // Drill-down click handler
                td.click(function() {
                    var name = $("#dimension-view-heat-map-" + heatMapId).attr('dimension')
                    var value = cell.value
                    var dimensionValues = parseDimensionValues(window.location.search)
                    dimensionValues[name] = value
                    window.location.search = encodeDimensionValues(dimensionValues)
                })
            })
            tbody.append(tr)
        })
        table.append(tbody)

        container.append(table)
    })
}

/** @return A {"size": x, "unit": y} object that best describes @param millis */
function describeMillis(millis) {
    var descriptors = [
        [2592000000, "MONTHS"],
        [604800000, "WEEKS"],
        [86400000, "DAYS"],
        [3600000, "HOURS"]
    ]

    for (var i = 0; i < descriptors.length; i++) {
        if (millis >= descriptors[i][0] && millis % descriptors[i][0] == 0) {
            return {
                "sizeMillis": descriptors[i][0],
                "size": millis / descriptors[i][0],
                "unit": descriptors[i][1]
            }
        }
    }

    return null
}

function toMillis(size, unit) {
    if (unit == 'SECONDS') {
        return size * 1000
    } else if (unit == 'MINUTES') {
        return size * 60 * 1000
    } else if (unit == 'HOURS') {
        return size * 60 * 60 * 1000
    } else if (unit == 'DAYS') {
        return size * 24 * 60 * 60 * 1000
    } else if (unit == 'WEEKS') {
        return size * 7 * 24 * 60 * 60 * 1000
    } else if (unit == 'MONTHS') {
        return size * 30 * 24 * 60 * 60 * 1000
    }
}

function getLocalTimeZone() {
    var timeZone = jstz()
    var utcOffset = timeZone.utc_offset
    var utcOffsetHours = Math.abs(utcOffset) / 60
    var utcOffsetMinutes = Math.abs(utcOffset) % 60
    var utcOffsetMagnitude = Math.abs(utcOffsetHours)

    var formatted = ""
    formatted += utcOffset < 0 ? "-" : ""
    formatted += utcOffsetMagnitude < 10 ? "0" + utcOffsetMagnitude : utcOffsetMagnitude
    formatted += ":"
    formatted += utcOffsetMinutes < 10 ? "0" + utcOffsetMinutes : utcOffsetMinutes
    formatted += " " + timeZone.timezone_name

    return formatted
}