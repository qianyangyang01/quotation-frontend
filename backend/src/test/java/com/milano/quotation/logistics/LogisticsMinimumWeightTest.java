package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsMinimumWeightTest {
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
