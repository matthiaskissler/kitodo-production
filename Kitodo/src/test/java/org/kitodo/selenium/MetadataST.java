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

package org.kitodo.selenium;

import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.kitodo.data.database.beans.User;
import org.kitodo.production.services.ServiceManager;
import org.kitodo.selenium.testframework.BaseTestSelenium;
import org.kitodo.selenium.testframework.Pages;

public class MetadataST extends BaseTestSelenium {

    @Before
    public void login() throws Exception {
        User metadataUser = ServiceManager.getUserService().getByLogin("verylast");
        Pages.getLoginPage().goTo().performLogin(metadataUser);
    }

    @Test
    public void hideStructureDataTest() throws Exception {
        Pages.getProcessesPage().goTo().editMetadata();
        assertFalse(Pages.getMetadataEditorPage().isStructureTreeFormVisible());
    }

    @After
    public void logout() throws Exception {
        Pages.getTopNavigation().logout();
    }
}
