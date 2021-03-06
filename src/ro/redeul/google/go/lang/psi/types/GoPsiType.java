package ro.redeul.google.go.lang.psi.types;

import com.intellij.psi.PsiNamedElement;
import ro.redeul.google.go.lang.psi.GoPsiElement;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: Aug 30, 2010
 * Time: 8:58:40 PM
 */
public interface GoPsiType extends GoPsiElement, PsiNamedElement {

    public static final GoPsiType[] EMPTY_ARRAY = new GoPsiType[0];

//    boolean isIdentical(GoPsiType goType);
}
