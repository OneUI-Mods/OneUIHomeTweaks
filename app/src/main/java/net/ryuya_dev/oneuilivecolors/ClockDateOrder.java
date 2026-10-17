package net.ryuya_dev.oneuilivecolors;

/** Accept only the native Samsung time + separator + date composition. */
final class ClockDateOrder {
  record Parts(int timeEnd, int dateStart) {}

  static Parts parts(String rendered, String time, String spokenTime, String date) {
    if (rendered == null || date == null || date.isEmpty()) return null;
    for (String candidate : new String[] {time, spokenTime}) {
      if (candidate == null || candidate.isEmpty()) continue;
      for (String separator : new String[] {"  ", "   "})
        if (rendered.equals(candidate + separator + date))
          return new Parts(candidate.length(), candidate.length() + separator.length());
    }
    return null;
  }

  private ClockDateOrder() {}
}
