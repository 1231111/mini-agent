package com.miniagent.agent.browser;

/** Read-only DOM projections. Never call checkValidity/reportValidity: they dispatch application events. */
final class BrowserFormSupport {
    private BrowserFormSupport() {}

    static final String SELECTOR = "input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=reset]):not([type=image]),"
            + "textarea,select,[role=textbox],[role=combobox],[role=listbox],[role=checkbox],[role=radio],[role=switch],[contenteditable=true]";
    static final int MAX_FIELDS = 200;
    static final String READ_FIELD = """
            el => {
              const text = v => String(v ?? '').slice(0, 8000);
              const tag = el.tagName.toLowerCase();
              const type = tag === 'input' ? el.type : (el.getAttribute('role') || tag);
              const password = type === 'password';
              const native = ['input','textarea','select'].includes(tag);
              const style = getComputedStyle(el), box = el.getBoundingClientRect();
              const visible = box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
              const labelled = (el.getAttribute('aria-labelledby') || '').split(/\\s+/)
                .map(id => el.ownerDocument.getElementById(id)?.textContent || '').join(' ').trim();
              const label = el.getAttribute('aria-label') || labelled ||
                [...(el.labels || [])].map(l => l.textContent).join(' ').trim() || el.getAttribute('placeholder') || '';
              const value = native ? String(el.value ?? '') : String(el.getAttribute('aria-valuetext') ?? el.textContent ?? '');
              const v = el.validity;
              const validity = v ? Object.fromEntries(['valid','valueMissing','typeMismatch','patternMismatch','tooLong',
                'tooShort','rangeUnderflow','rangeOverflow','stepMismatch','badInput','customError'].map(k => [k, v[k]])) : null;
              const options = tag === 'select' ? [...el.options] : [];
              const files = type === 'file' ? [...(el.files || [])].map(f => ({name:f.name,size:f.size,type:f.type})) : [];
              return {
                tag,type,role:el.getAttribute('role') || '',id:el.id || '',name:el.getAttribute('name') || '',label:text(label),
                formId:el.form?.id || '',native,visible,required:!!el.required || el.getAttribute('aria-required') === 'true',
                disabled:!!el.matches(':disabled') || el.getAttribute('aria-disabled') === 'true',readOnly:!!el.readOnly,
                multiple:!!el.multiple,accept:el.getAttribute('accept') || '',
                constraints:Object.fromEntries(['min','max','step','minlength','maxlength','pattern','placeholder','autocomplete']
                  .filter(k => el.hasAttribute(k)).map(k => [k,text(el.getAttribute(k))])),
                value:password || type === 'file' ? null : text(value),valueRedacted:password,
                hasValue:password ? value.length > 0 : null,valueTruncated:!password && value.length > 8000,
                checked:tag === 'input' && ['checkbox','radio'].includes(type) ? !!el.checked :
                  (el.hasAttribute('aria-checked') ? el.getAttribute('aria-checked') : null),
                selectedValues:tag === 'select' ? [...el.selectedOptions].map(o => o.value) : null,
                options:options.slice(0,200).map(o => ({label:text(o.label),value:text(o.value),selected:o.selected,
                  disabled:o.disabled || !!o.closest('optgroup:disabled')})),optionsTruncated:options.length > 200,
                files,validity,validationMessage:password ? '' : text(el.validationMessage || ''),
                ariaInvalid:el.getAttribute('aria-invalid'),
                describedBy:text((el.getAttribute('aria-describedby') || '').split(/\\s+/)
                  .map(id => el.ownerDocument.getElementById(id)?.textContent || '').join(' ').trim())
              };
            }
            """;
}
