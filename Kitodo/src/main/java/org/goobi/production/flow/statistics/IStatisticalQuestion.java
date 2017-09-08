/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.goobi.production.flow.statistics;

import de.intranda.commons.chart.renderer.IRenderer;
import de.intranda.commons.chart.results.DataTable;

import java.util.List;

import org.goobi.production.flow.statistics.enums.CalculationUnit;
import org.goobi.production.flow.statistics.enums.TimeUnit;

/**
 * This interface defines the required methods for any implementations of
 * statistical requests to be used with the StatisticalManager. There is an
 * extension to this interface for implementations which needs time restriction
 * to be set.
 * 
 * @author Wulf Riebensahm
 * @author Steffen Hankiewicz
 */
public interface IStatisticalQuestion {

    /**
     * Get a list of DataTable Objects, which can be used to display the results of
     * the data request.
     * 
     * @param dataSource
     *            List
     * @return List &lt;DataTable&gt;
     */
    List<DataTable> getDataTables(List dataSource);

    /**
     * This method is used to set a grouping unit for time based data.
     * 
     * @param timeUnit
     *            TimeUnit
     */
    void setTimeUnit(TimeUnit timeUnit);

    /**
     * This method sets the calculation unit needed for specific requests. Since not
     * all implementations will support this feature future implementations of this
     * method will throw an UnsupportedOperationException.
     * 
     * @param cu
     *            CalculationUnit
     */
    void setCalculationUnit(CalculationUnit cu);

    /**
     * Get all IRenderer where the DataTable matrix should be used inverted.
     * 
     * @param inRenderer
     *            as {@link IRenderer}
     * @return true, if rendering should use inverted {@link DataTable}
     */
    Boolean isRendererInverted(IRenderer inRenderer);

    /**
     * Get number format pattern.
     * 
     * @return number format pattern for rendering the results
     */
    String getNumberFormatPattern();
}
