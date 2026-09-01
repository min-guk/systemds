args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 2) stop("usage: plot_interim_results.R INPUT_CSV OUTPUT_DIR")
input <- args[1]
out <- args[2]
dir.create(out, recursive = TRUE, showWarnings = FALSE)
d <- read.csv(input, stringsAsFactors = FALSE)

planners <- c("DP", "FedAll", "Heuristic", "MinST")
workloads <- c("kmeans", "pca", "lm", "l2svm", "logreg", "als", "steplm")
profiles <- c("lan", "wan_light", "wan_mid")
cols <- c(DP = "#2878B5", FedAll = "#D94841", Heuristic = "#E69F00", MinST = "#009E73")
pch <- c(DP = 16, FedAll = 17, Heuristic = 15, MinST = 18)

for (profile in profiles) {
  png(file.path(out, paste0("runtime_", profile, "_interim_284.png")),
      width = 1800, height = 2100, res = 180)
  par(mfrow = c(4, 2), mar = c(4.2, 4.7, 3.2, 1.0), oma = c(2, 1, 6, 1))
  for (workload in workloads) {
    x <- d[d$profile == profile & d$workload == workload, ]
    ylim <- range(x$execution_seconds, finite = TRUE)
    pad <- max(diff(ylim) * 0.10, max(ylim) * 0.03)
    plot(NA, xlim = c(1, 4), ylim = c(max(0, ylim[1] - pad), ylim[2] + pad),
         xaxt = "n", xlab = "Workers", ylab = "Execution time (s)",
         main = workload, bty = "l")
    axis(1, at = 1:4)
    grid(col = "#E6E6E6", lty = 1)
    for (planner in planners) {
      z <- x[x$planner == planner, ]
      z <- z[order(z$workers), ]
      if (nrow(z) > 0) lines(z$workers, z$execution_seconds, type = "b",
                             col = cols[planner], pch = pch[planner], lwd = 2, cex = 1.1)
    }
  }
  plot.new()
  legend("center", legend = planners, col = cols[planners], pch = pch[planners],
         lwd = 2, ncol = 2, bty = "n", cex = 1.15)
  mtext(paste0("Interim authenticated Docker runtimes — ", profile, " — 284/336 cells"),
        outer = TRUE, side = 3, line = 3.4, cex = 1.35, font = 2)
  mtext("Missing MinST points are unexecuted, not zero. Stitched committed binaries; diagnostic, not final homogeneous run.",
        outer = TRUE, side = 3, line = 1.4, cex = 0.82)
  dev.off()
}

keys <- paste(d$workers, d$workload, d$profile, sep = "|")
complete.keys <- names(which(table(keys) == 4))
m <- d[keys %in% complete.keys, ]
dp <- m[m$planner == "DP", c("workers", "workload", "profile", "execution_seconds")]
names(dp)[4] <- "dp_seconds"
m <- merge(m, dp, by = c("workers", "workload", "profile"))
m$ratio_to_dp <- m$execution_seconds / m$dp_seconds

png(file.path(out, "matched_four_planner_ratios_interim_284.png"),
    width = 1800, height = 1200, res = 180)
par(mar = c(5, 5, 5, 2))
ratio.list <- lapply(planners, function(planner) m$ratio_to_dp[m$planner == planner])
boxplot(ratio.list, names = planners, col = unname(cols[planners]),
        ylab = "Execution time / DP execution time", xlab = "Planner",
        main = paste0("Four-planner matched cells (n=", length(complete.keys), ") — interim 284/336"),
        outline = TRUE, pch = 16)
abline(h = 1, col = "#333333", lty = 2, lwd = 2)
stripchart(ratio.list, vertical = TRUE, method = "jitter", add = TRUE,
           pch = 21, bg = "white", col = "#333333", cex = 0.8)
mtext("Lower is faster. Expected: MinST <= DP <= Heuristic and FedAll. Stitched binaries; diagnostic only.",
      side = 3, line = 0.8, cex = 0.85)
dev.off()

counts <- table(factor(d$planner, levels = planners))
png(file.path(out, "coverage_by_planner_interim_284.png"),
    width = 1600, height = 1050, res = 180)
par(mar = c(5, 5, 5, 2))
bars <- barplot(counts, col = unname(cols[planners]), ylim = c(0, 92),
                ylab = "Authenticated unique successful cells", xlab = "Planner",
                main = "Current no-duplicate coverage — 284/336 cells")
abline(h = 84, col = "#333333", lty = 2, lwd = 2)
text(bars, counts + 3, labels = paste0(counts, "/84"), font = 2)
mtext("DP, FedAll, Heuristic complete; MinST has 32 successes and 52 cells remaining.",
      side = 3, line = 0.8, cex = 0.9)
dev.off()
