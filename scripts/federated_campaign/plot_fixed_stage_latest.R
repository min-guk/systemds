#!/usr/bin/env Rscript

args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 10) {
  stop(paste(
    "usage: plot_fixed_stage_latest.R <csv> <metric> <ylabel> <title>",
    "<subtitle> <footer> <column-order-csv> <total-cells> <png> <svg>"
  ))
}

csv_path <- args[[1]]
metric <- args[[2]]
y_label <- args[[3]]
plot_title <- args[[4]]
plot_subtitle <- args[[5]]
plot_footer <- args[[6]]
workloads <- strsplit(args[[7]], ",", fixed = TRUE)[[1]]
total_cells <- as.integer(args[[8]])
png_path <- args[[9]]
svg_path <- args[[10]]

data <- read.csv(csv_path, stringsAsFactors = FALSE)
profiles <- c("lan", "wan_light", "wan_mid")
profile_labels <- c(lan = "LAN", wan_light = "WAN-Light", wan_mid = "WAN-Mid")
planners <- c("FedAll", "Heuristic", "DP", "Exact")
legend_labels <- c(FedAll = "ALL", Heuristic = "Heuristic", DP = "DP", Exact = "Exact")
colors <- c(FedAll = "#c44e52", Heuristic = "#55a868", DP = "#e17c05", Exact = "#4c72b0")
symbols <- c(FedAll = 0, Heuristic = 1, DP = 5, Exact = 2)
label_positions <- c(FedAll = 3, Heuristic = 1, DP = 3, Exact = 1)

if (!(metric %in% names(data))) {
  stop(paste("missing metric column:", metric))
}
if (length(workloads) != 7 || length(unique(workloads)) != 7) {
  stop("expected seven unique workloads")
}
if (nrow(data) == 0 || nrow(data) > total_cells) {
  stop("invalid successful-row count")
}
data[[metric]] <- as.numeric(data[[metric]])
if (any(!is.finite(data[[metric]])) || any(data[[metric]] < 0)) {
  stop("metric contains invalid values")
}

column_ymax <- setNames(rep(1, length(workloads)), workloads)
for (workload in workloads) {
  values <- data[data$workload == workload, metric]
  observed <- if (length(values) > 0) max(values) else 0
  column_ymax[[workload]] <- if (observed > 0) observed * 1.16 else 1
}

draw_graph <- function() {
  panel_ids <- matrix(2:22, nrow = length(profiles), byrow = TRUE)
  layout(
    rbind(rep(1, length(workloads)), panel_ids),
    heights = c(0.17, rep(0.2767, length(profiles)))
  )
  par(
    oma = c(1.25, 4.0, 0.2, 0.45),
    mgp = c(2.0, 0.58, 0),
    tcl = -0.24,
    las = 1,
    family = "sans"
  )

  par(mar = c(0, 0, 0, 0))
  plot.new()
  text(0.5, 0.84, plot_title, cex = 1.16, font = 2)
  text(0.5, 0.58, plot_subtitle, cex = 0.69, col = "#5f5f5f")
  legend(
    x = 0.5,
    y = 0.25,
    legend = legend_labels[planners],
    col = colors[planners],
    pch = symbols[planners],
    lty = 1,
    lwd = 1.55,
    horiz = TRUE,
    bty = "n",
    cex = 0.86,
    xjust = 0.5,
    yjust = 0.5
  )
  text(0.5, 0.03, plot_footer, cex = 0.56, col = "#777777")

  for (profile_index in seq_along(profiles)) {
    profile <- profiles[[profile_index]]
    for (workload_index in seq_along(workloads)) {
      workload <- workloads[[workload_index]]
      block <- data[data$profile == profile & data$workload == workload, ]
      bottom_row <- profile_index == length(profiles)
      ymax <- column_ymax[[workload]]
      par(mar = c(if (bottom_row) 3.45 else 2.05, 2.3, 2.0, 0.48))

      plot(
        NA,
        xlim = c(0.78, 4.22),
        ylim = c(0, ymax),
        xaxt = "n",
        xlab = if (bottom_row) "Workers" else "",
        ylab = "",
        main = if (profile_index == 1) toupper(workload) else "",
        cex.main = 0.98,
        cex.lab = 0.82,
        cex.axis = 0.70,
        bty = "l"
      )
      axis(1, at = 1:4, labels = 1:4, cex.axis = 0.70)
      abline(h = pretty(c(0, ymax)), col = "#dddddd", lty = 2, lwd = 0.72)

      if (workload_index == 1) {
        mtext(profile_labels[[profile]], side = 2, line = 3.0, cex = 0.84, las = 0)
      }

      for (planner in planners) {
        values <- rep(NA_real_, 4)
        series <- block[block$planner == planner, c("workers", metric)]
        if (nrow(series) > 0) {
          values[as.integer(series$workers)] <- as.numeric(series[[metric]])
        }
        lines(
          1:4,
          values,
          type = "o",
          col = colors[[planner]],
          pch = symbols[[planner]],
          lwd = 1.55,
          cex = 0.70
        )
        observed <- which(is.finite(values))
        if (length(observed) > 0) {
          text(
            observed,
            values[observed],
            labels = sprintf("%.2f", values[observed]),
            pos = label_positions[[planner]],
            offset = 0.22,
            cex = 0.43,
            col = colors[[planner]],
            xpd = FALSE
          )
        }
      }

      count <- nrow(block)
      if (count == 0) {
        text(2.5, ymax * 0.50, "not yet executed", cex = 0.70, col = "#999999")
      } else if (count < 16) {
        text(4.12, ymax * 0.96, paste0("partial ", count, "/16"),
             cex = 0.60, col = "#777777", adj = c(1, 1))
      }
    }
  }

  mtext(y_label, outer = TRUE, side = 2, line = 1.60, cex = 0.91, las = 0)
}

dir.create(dirname(png_path), recursive = TRUE, showWarnings = FALSE)
png(png_path, width = 4200, height = 1800, res = 200, pointsize = 12)
draw_graph()
dev.off()

svg(svg_path, width = 21, height = 9, pointsize = 12)
draw_graph()
dev.off()
