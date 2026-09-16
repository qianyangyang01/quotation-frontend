package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsMinimumWeightTest {
    @Test void readsShortBillingPhrasesAndFirstWeightWithRounding() {
        weight("美国单票计费50克起，其他国家单票计费10克起；","US",.05);
        weight("美国单票计费50克起，其他国家单票计费10克起；","GB",.01);
        weight("美国最低50G计费；加拿大最低100G计费","CA",.1);
        weight("美国单票计费50克起，超过按1g进位","US",.05);
        weight("单票单件计费;首重50g，续重按1g计算重量, 实重材积取其大者计费","US",.05);
        weight("单票单件计费，首重0.5KG，续重0.5KG，实重和体积重取大者计费","JP",.5);
        assertNull(LogisticsMinimumWeight.fromNotes("重派费首重1KG，续重1KG；按1KG进位","US").kg());
    }
    @Test void countryScopeSurvivesSemicolonButDoesNotLeakToOtherCountries() {
        var note="6. 加拿大：体积重低于实际重量2倍的，按照实际重量收费;达到或超过实际重量2倍的，按照体积重量收取。最低计费重0.05KG";
        weight(note,"CA",.05);assertNull(LogisticsMinimumWeight.fromNotes(note,"DE").kg());
        note="4.墨西哥、美国、加拿大：0<W≤30KG，美国最低计费重50g";
        weight(note,"US",.05);assertNull(LogisticsMinimumWeight.fromNotes(note,"CA").kg());
        assertNull(LogisticsMinimumWeight.fromNotes(note,"MX").kg());
    }
    private void weight(String note,String country,double expected) {
        var result=LogisticsMinimumWeight.fromNotes(note,country);
        assertFalse(result.conflict(),result.evidence());assertNotNull(result.kg(),note);assertEquals(expected,result.kg().doubleValue());
    }
    @Test void resolvesCountrySpecificAndOtherCountryNotes() {
        var note="美国50G起重，其他国家10G起重";weight(note,"US",.05);weight(note,"GB",.01);
        note="澳大利亚单票计费起重100G，韩国单票计费起重1KG,其余国家单票计费起重50克";
        weight(note,"AU",.1);weight(note,"KR",1);weight(note,"US",.05);
        note="美国、加拿大、巴西：最低重量为50G，不足50G按50G计费，超过50G按1G进位；日本：最低计费重0.5KG";
        weight(note,"US",.05);weight(note,"CA",.05);weight(note,"JP",.5);assertNull(LogisticsMinimumWeight.fromNotes(note,"GB").kg());
    }
    @Test void handlesExemptionsAndDoesNotUseRoundingOrReturnChargesAsMinimums() {
        var note="英国无50g起重；美国30g起重，不足30g的部分按30g计价；其余国家50g起重";
        weight(note,"GB",0);weight(note,"US",.03);weight(note,"CA",.05);
        assertNull(LogisticsMinimumWeight.fromNotes("销毁费10元/KG，不足1kg按照1kg计费；不足0.01KG按0.01KG进位；重派费用：50克起重","US").kg());
        weight("以G为单位进位；不足50G按50G计费","US",.05);
        weight("单票单件计费; 首重为50g，不足50g均按照50g计费，以1g进位制","US",.05);
    }
    @Test void reportsConflictsRatherThanChoosingTheLowerMinimum() {
        assertTrue(LogisticsMinimumWeight.fromNotes("美国50g起重；美国100g起重","US").conflict());
    }
}
