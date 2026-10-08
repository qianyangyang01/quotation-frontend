package com.milano.quotation.fob;

import java.math.*;
import java.util.*;
import java.util.regex.*;

/** Conservative source parsing. Unconsumed prices/conditions are errors, never silent guesses. */
public final class FobSourceParser {
    private FobSourceParser() {}
    private static final String NUM = "\\d+(?:\\.\\d+)?";
    private static final String UNIT = "[件双套盒条个瓶张]";
    private static final Pattern PRICE = Pattern.compile("(≥\\d+|\\d+-\\d+|\\d+" + UNIT + "(?:以上|起批|起)?|\\d+(?:以上|起批|起)|\\d+(?=单价|价格))\\s*(?:" + UNIT + ")?\\s*(?:单价|价格)?\\s*:?\\s*[¥￥]?(" + NUM + ")");
    private static final Pattern FREIGHT = Pattern.compile("(\\d+)\\s*(?:" + UNIT + ")?\\s*(以上|以内|内)?\\s*(?:(?:预拍|试拍|预计|预估|大概|总|发物流|运费|运价|大约|约)\\s*)*:?\\s*(包邮|免运费|" + NUM + ")");
    public record Tier(int minQty, Integer maxQty, BigDecimal unitPriceCny, String unit) {}
    public record Freight(int quantity, BigDecimal totalFreightCny, BigDecimal unitFreightCny, boolean estimated, String basis) {}
    public record Parsed(int minOrderQty, int orderMultiple, List<Tier> priceTiers, Freight freight, List<String> notices) {}

    public static Parsed parse(String moqRaw, String priceRaw, String freightRaw) {
        var notices = new ArrayList<String>();
        String moq = clean(moqRaw), prices = clean(priceRaw), freight = clean(freightRaw);
        int minimum = 0, multiple = 1;
        if (!moq.isBlank()) {
            var m = Pattern.compile("^(\\d+)(?:的倍数下单|起订|" + UNIT + "起订|" + UNIT + ")?;?$").matcher(moq);
            if (!m.matches()) fail("起订量无法识别，请填写正整数或“10的倍数下单”");
            minimum = integer(m.group(1));
            if (moq.contains("倍数")) multiple = minimum;
        }
        if (prices.isBlank()) fail("缺少采购价格原文");
        if (Pattern.compile("版费|打样|协议价|批发价|独立包装|线下|更新|\\d{4}\\.").matcher(prices).find())
            fail("采购价格含额外费用、不同方案或更新说明，请先明确本次适用的数量与单价");
        prices = prices.replaceAll(",\\s*(?=单价|价格)", " ");
        // Suppliers sometimes put the price before its quantity on separate lines.
        prices = prices.replaceAll("(?m)^\\s*(" + NUM + ")\\s*\\n\\s*((?:≥)?\\d+(?:-\\d+)?" + UNIT + "(?:起批|以上)?)", "$2:$1");
        prices = prices.replaceAll("(?m)(?:^|[;\\n])\\s*(\\d+)-(" + NUM + ")(?=\\s*(?:[;\\n]|$))", ";$1单价$2");
        var unitMatcher = Pattern.compile("(?:\\d+\\s*|/)(" + UNIT + ")").matcher(prices);
        var units = new HashSet<String>();
        while (unitMatcher.find()) units.add(unitMatcher.group(1));
        if (units.size() > 1) fail("采购阶梯使用不同计价单位，请先明确统一的数量单位");
        String purchaseUnit = units.isEmpty() ? "件" : units.iterator().next();
        var tiers = new ArrayList<Tier>();
        BigDecimal base = null;
        for (String line : prices.split("[;,\\n]+")) {
            line = line.trim();
            if (line.isBlank() || line.matches("单价:?")) continue;
            if (line.matches("(?:简装.*一包|不含税运)")) { notices.add(line); continue; }
            var m = PRICE.matcher(line);
            StringBuilder rest = new StringBuilder(); int end = 0;
            while (m.find()) {
                rest.append(line, end, m.start()); end = m.end();
                String spec = m.group(1);
                var ns = Pattern.compile("\\d+").matcher(spec); ns.find(); int low = integer(ns.group());
                Integer high = spec.contains("-") && ns.find() ? integer(ns.group()) : null;
                tiers.add(new Tier(low, high, amount(m.group(2)), purchaseUnit));
            }
            rest.append(line.substring(end));
            String residual = rest.toString().replaceAll("单价|单件|外套|文胸\\+短裤|元|人民币|[¥￥:]", "");
            residual = residual.replaceAll("/[件双套盒条个瓶张]", "");
            residual = residual.trim();
            if (!residual.isBlank()) {
                if (!residual.matches(NUM) || base != null) fail("采购价格存在未识别内容：“" + line + "”，请按“起订数量 单价”分行填写");
                base = amount(residual);
            }
        }
        if (tiers.isEmpty() && base == null) fail("没有识别到有效采购价格");
        tiers.sort(Comparator.comparingInt(Tier::minQty));
        if (minimum == 0 && base == null && !tiers.isEmpty()) minimum = tiers.getFirst().minQty;
        if (minimum == 0) fail("缺少起订量，不能默认按1件报价");
        if (base != null && (tiers.isEmpty() || minimum < tiers.getFirst().minQty)) tiers.addFirst(new Tier(minimum, null, base, purchaseUnit));
        else if (base != null) notices.add("基准价低于起订数量的部分不参与报价，采用起订数量对应阶梯");
        var effective = new ArrayList<Tier>();
        for (int i = 0; i < tiers.size(); i++) {
            var t = tiers.get(i); Integer next = i + 1 < tiers.size() ? tiers.get(i + 1).minQty : null;
            if (next != null && next <= t.minQty) fail("采购阶梯数量重复");
            if (t.maxQty != null && (t.maxQty < t.minQty || next != null && t.maxQty >= next)) fail("采购阶梯区间重叠或倒置");
            if (next != null && t.maxQty != null && t.maxQty != next - 1) fail("采购阶梯数量区间不连续");
            Integer high = t.maxQty != null ? t.maxQty : next == null ? null : next - 1;
            if (high != null && high < minimum) continue;
            effective.add(new Tier(Math.max(t.minQty, minimum), high, t.unitPriceCny, t.unit));
        }
        if (effective.isEmpty() || effective.getFirst().minQty > minimum) fail("起订量对应的采购价格缺失");
        if (effective.getLast().maxQty != null) notices.add("采购价格只明确到" + effective.getLast().maxQty + "，更大数量暂无有效价格");
        return new Parsed(minimum, multiple, List.copyOf(effective), freight(freight, effective, notices), notices);
    }

    private static Freight freight(String raw, List<Tier> tiers, List<String> notices) {
        if (raw.isBlank()) fail("缺少运费原文");
        boolean estimated = raw.matches("(?s).*(预计|预拍|预估|大概|大约|左右|约).*" );
        if (raw.matches("(?:包邮|免运费|0)(?:[;。\\s]*)")) return new Freight(100, BigDecimal.ZERO, BigDecimal.ZERO, false, "包邮");
        if (raw.matches("(?s).*(另加|另计|续重|首重|每公斤|每千克|到付|议价).*")) fail("运费包含附加计费条件，请明确批量总运费");
        var free = Pattern.compile("(\\d+)\\s*元\\s*(?:以上|起)\\s*包邮").matcher(raw);
        boolean amountFree = false;
        if (free.find()) {
            int threshold = integer(free.group(1));
            boolean applicable = tiers.stream().filter(t -> t.minQty <= 100 && (t.maxQty == null || t.maxQty >= 100))
                        .anyMatch(t -> t.unitPriceCny.multiply(BigDecimal.valueOf(100)).compareTo(BigDecimal.valueOf(threshold)) >= 0);
            if (!applicable) fail("条件包邮未明确适用于百件，请补充适用的批量总运费");
            notices.add("按100件采购金额满足“" + free.group() + "”识别包邮");
            amountFree = true;
            raw = free.replaceFirst("");
        }
        raw = raw.replace("一件", "1件");
        var matcher = FREIGHT.matcher(raw);
        var batches = new TreeMap<Integer, BigDecimal>();
        if (amountFree) batches.put(100, BigDecimal.ZERO);
        StringBuilder residual = new StringBuilder(); int end = 0;
        while (matcher.find()) {
            residual.append(raw, end, matcher.start()); end = matcher.end();
            if (!Pattern.compile(UNIT + "|运费|运价|以上|内|:|\\s").matcher(matcher.group()).find()) fail("运费数量和金额之间缺少明确分隔");
            int qty = integer(matcher.group(1)); String value = matcher.group(3);
            BigDecimal fee = value.equals("包邮") || value.equals("免运费") ? BigDecimal.ZERO : amount(value);
            var previous = batches.putIfAbsent(qty, fee);
            if (previous != null && previous.compareTo(fee) != 0) fail("同一运费数量对应不同金额，请核对原文");
            if (fee.signum() == 0 && "以上".equals(matcher.group(2)) && qty < 100) {
                var hundred = batches.putIfAbsent(100, BigDecimal.ZERO);
                if (hundred != null && hundred.signum() != 0) fail("包邮条件与百件运费冲突，请核对原文");
            }
        }
        residual.append(raw.substring(end));
        if (!residual.toString().replaceAll("预拍|试拍|预计|预估|大概|大约|左右|运费|运价|人民币|元|[¥￥:;,/。\\s]", "").isBlank())
            fail("运费含未识别内容或条件，请核对数量及总运费");
        var chosen = batches.ceilingEntry(100);
        if (chosen == null) fail("未识别到100件或更大批量的总运费，请补充数量与金额");
        if (estimated) notices.add("运费为原表预估/预拍金额，保留其估算性质");
        return new Freight(chosen.getKey(), chosen.getValue(), chosen.getValue().divide(BigDecimal.valueOf(chosen.getKey()), 10, RoundingMode.HALF_UP).stripTrailingZeros(), estimated,
                chosen.getKey() + "件总运费 " + chosen.getValue().toPlainString() + " ÷ " + chosen.getKey());
    }

    private static String clean(String s) { return s == null ? "" : s.trim().replace('\r', '\n').replace('：', ':').replace('；', ';').replace('，', ',').replace('—', '-').replace('–', '-').replace(">=", "≥"); }
    private static int integer(String s) {
        try { int value = Integer.parseInt(s); if (value <= 0 || value > 100_000_000) fail("数量须为有效正整数"); return value; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("数量超出有效范围"); }
    }
    private static BigDecimal amount(String s) { var n = new BigDecimal(s); if (n.signum() < 0 || n.compareTo(new BigDecimal("100000000")) > 0) fail("金额超出有效范围"); return n.stripTrailingZeros(); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
