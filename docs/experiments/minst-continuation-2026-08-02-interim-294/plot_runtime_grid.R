args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 3) stop("usage: plot_runtime_grid.R INPUT_CSV OUTPUT_PNG COMPLETED_CELLS")

input <- args[1]
output <- args[2]
completed <- args[3]
d <- read.csv(input, stringsAsFactors = FALSE)

planners <- c("DP", "FedAll", "Heuristic", "MinST")
workloads <- c("kmeans", "pca", "lm", "l2svm", "logreg", "als", "steplm")
profiles <- c("lan", "wan_light", "wan_mid")
profile.labels <- c(lan = "LAN", wan_light = "WAN-light", wan_mid = "WAN-mid")
cols <- c(DP = "#2878B5", FedAll = "#D94841", Heuristic = "#E69F00", MinST = "#009E73")
pch <- c(DP = 16, FedAll = 17, Heuristic = 15, MinST = 18)

png(output, width = 4200, height = 1900, res = 200)
layout(matrix(seq_len(21), nrow = 3, ncol = 7, byrow = TRUE))
par(mar = c(3.6, 4.0, 2.8, 0.9), oma = c(3.0, 4.0, 7.0, 1.0))

workload.limits <- lapply(workloads, function(workload) {
  values <- d$execution_seconds[d$workload == workload]
  limits <- range(values, finite = TRUE)
  pad <- max(diff(limits) * 0.06, max(limits) * 0.035, 1)
  c(max(0, limits[1] - pad), limits[2] + pad)
})
names(workload.limits) <- workloads

for (profile in profiles) {
  for (workload in workloads) {
    x <- d[d$profile == profile & d$workload == workload, ]
    plot(
      NA,
      xlim = c(0.85, 4.15),
      ylim = workload.limits[[workload]],
      xaxt = "n",
      yaxt = "n",
      xlab = "",
      ylab = "",
      main = "",
      bty = "l",
      cex.main = 1.0
    )
    axis(1, at = 1:4, cex.axis = 0.78)
    axis(2, cex.axis = 0.70, las = 1)
    grid(col = "#E8E8E8", lty = 1)
    for (planner in planners) {
      z <- x[x$planner == planner, ]
      z <- z[order(z$workers), ]
      if (nrow(z) > 0) {
        lines(
          z$workers,
          z$execution_seconds,
          type = "b",
          col = cols[planner],
          pch = pch[planner],
          lwd = 2.0,
          cex = 0.85
        )
      }
    }
  }
}

par(fig = c(0, 1, 0, 1), new = TRUE, mar = c(0, 0, 0, 0))
plot.new()
plot.window(xlim = c(0, 1), ylim = c(0, 1), xaxs = "i", yaxs = "i")
text(0.5, 0.988, paste0("Authenticated Docker execution times — ", completed, "/336 cells"),
     cex = 1.35, font = 2)
text(0.5, 0.961,
     "Rows: execution environment · Columns: workload · Missing MinST points are unexecuted (not zero)",
     cex = 0.92)
legend(
  x = 0.5,
  y = 0.931,
  xjust = 0.5,
  yjust = 0.5,
  legend = planners,
  col = cols[planners],
  pch = pch[planners],
  lwd = 2,
  horiz = TRUE,
  bty = "n",
  cex = 0.95
)
column.centers <- seq(0.103, 0.947, length.out = length(workloads))
text(column.centers, 0.890, toupper(workloads), cex = 0.95, font = 2)
row.centers <- c(0.733, 0.475, 0.217)
text(0.018, row.centers, profile.labels[profiles], srt = 90, cex = 0.98, font = 2)
text(0.5, 0.014, "Workers", cex = 1.1)
text(0.006, 0.5, "Execution time (seconds)", srt = 90, cex = 1.1)
dev.off()
