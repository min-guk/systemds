#!/usr/bin/env Rscript

args <- commandArgs(trailingOnly = TRUE)
if (length(args) != 2) {
	stop("usage: plot_pruning_ablation.R ABLATION_CSV OUTPUT_DIRECTORY")
}

rows <- read.csv(args[[1]], check.names = FALSE, stringsAsFactors = FALSE)
output <- args[[2]]
variants <- c("baseline", "dominance", "support", "local", "global")
variant_labels <- c("Base", "+Dominance", "+Support", "+Local", "+Global")
canonical <- c("logreg", "l2svm", "pca", "als", "kmeans", "lm", "glm", "gnmf", "gmm", "steplm-compatible")
workloads <- canonical[canonical %in% unique(rows$workload)]
if (length(workloads) != 10L || !setequal(workloads, unique(rows$workload))) {
	stop("plot expects the canonical nine original non-STEP workloads plus steplm-compatible")
}

metric_matrix <- function(metric) {
	result <- matrix(NA_real_, nrow = length(workloads), ncol = length(variants),
		dimnames = list(workloads, variants))
	for (i in seq_along(workloads)) {
		for (j in seq_along(variants)) {
			match <- rows[rows$workload == workloads[[i]] & rows$variant == variants[[j]], ]
			if (nrow(match) != 1L) stop(sprintf("missing or duplicate plot row: %s/%s", workloads[[i]], variants[[j]]))
			result[i, j] <- match[[metric]][[1]]
		}
	}
	if (any(!is.finite(result)) || any(result <= 0)) stop(sprintf("invalid plot ratio in %s", metric))
	result
}

colors <- grDevices::colorRampPalette(c("#45a65a", "#fff0a6", "#dc4545"))(201)
color_for <- function(value) {
	level <- max(-1, min(1, log(value, base = 2)))
	colors[[round((level + 1) * 100) + 1]]
}

draw_heatmap <- function(values, heading) {
	par(mar = c(2.8, 8.7, 4.3, 1.0), xpd = NA)
	plot(NA, xlim = c(0.5, 5.5), ylim = c(10.5, 0.5), axes = FALSE,
		xlab = "", ylab = "", type = "n")
	for (i in seq_len(nrow(values))) {
		for (j in seq_len(ncol(values))) {
			graphics::rect(j - 0.49, i - 0.49, j + 0.49, i + 0.49,
				col = color_for(values[i, j]), border = "white", lwd = 1.5)
			label <- sprintf("%.2f", values[i, j])
			if (workloads[[i]] == "steplm-compatible" && variants[[j]] == "global") {
				global <- rows[rows$workload == workloads[[i]] & rows$variant == variants[[j]], ]
				if (grepl("UNSUPPORTED_CANONICAL", global$global_outcomes[[1]], fixed = TRUE) &&
					global$globalConsidered[[1]] == 0) label <- paste0(label, " [U]")
			}
			text(j, i, label, cex = 0.92, font = 2)
		}
	}
	axis(1, at = seq_along(variants), labels = variant_labels, tick = FALSE,
		line = -0.3, cex.axis = 0.86, font = 2)
	axis(2, at = seq_along(workloads), labels = workloads, tick = FALSE,
		las = 1, cex.axis = 0.93, font = 2)
	box(col = "#777777")
	title(main = heading, line = 2.5, cex.main = 1.35)
	mtext("ratio of three-repetition medians; lower is faster", side = 3,
		line = 1.0, cex = 0.88, col = "#444444")
}

draw_legend <- function() {
	par(mar = c(1.8, 13, 0.1, 13))
	plot(NA, xlim = c(-1, 1), ylim = c(0, 1), axes = FALSE, xlab = "", ylab = "", type = "n")
	breaks <- seq(-1, 1, length.out = length(colors) + 1)
	graphics::rect(breaks[-length(breaks)], 0.28, breaks[-1], 0.67, col = colors, border = NA)
	axis(1, at = c(-1, 0, 1), labels = c("0.5x", "1x (baseline)", "2x"),
		tick = FALSE, line = -0.7, cex.axis = 0.9)
	box(col = "#777777")
}

render_plot <- function(open_device) {
	open_device()
	on.exit(dev.off(), add = TRUE)
	layout(matrix(c(1, 2, 3, 3), nrow = 2, byrow = TRUE), heights = c(10, 2))
	par(oma = c(2.8, 0.8, 3.2, 0.8), family = "sans")
	draw_heatmap(metric_matrix("full_initial_planning_seconds_ratio_vs_baseline"), "Full initial planning")
	draw_heatmap(metric_matrix("optimizer_seconds_ratio_vs_baseline"), "Optimizer")
	draw_legend()
	mtext("Pruning ablation: original non-STEP 9 + separate compatible STEP-LM",
		side = 3, outer = TRUE, line = 1.0, cex = 1.35, font = 2)
	mtext("Compatible STEP-LM is a separate workload; original STEP-LM remains failed. [U] canonical global certificate unsupported (globalConsidered=0).",
		side = 1, outer = TRUE, line = 1.0, cex = 0.82)
}

render_plot(function() png(file.path(output, "latency.png"), width = 1800, height = 950,
	res = 150, type = "cairo", bg = "white"))
render_plot(function() svg(file.path(output, "latency.svg"), width = 12, height = 6.4,
	pointsize = 11, bg = "white"))
